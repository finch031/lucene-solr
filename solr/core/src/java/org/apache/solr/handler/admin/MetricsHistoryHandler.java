/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.admin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.Function;
import java.util.stream.Stream;

import com.google.common.annotations.VisibleForTesting;
import org.apache.solr.api.Api;
import org.apache.solr.api.ApiBag;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.cloud.NodeStateProvider;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.cloud.autoscaling.ReplicaInfo;
import org.apache.solr.client.solrj.cloud.autoscaling.Suggestion;
import org.apache.solr.client.solrj.cloud.autoscaling.VersionedData;
import org.apache.solr.cloud.Overseer;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.params.CollectionAdminParams;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.Base64;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.metrics.rrd.SolrRrdBackendFactory;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.solr.util.DefaultSolrThreadFactory;
import org.apache.zookeeper.KeeperException;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.ArcDef;
import org.rrd4j.core.Archive;
import org.rrd4j.core.Datasource;
import org.rrd4j.core.DsDef;
import org.rrd4j.core.FetchData;
import org.rrd4j.core.FetchRequest;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.Sample;
import org.rrd4j.graph.RrdGraph;
import org.rrd4j.graph.RrdGraphDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toMap;
import static org.apache.solr.common.params.CommonParams.ID;

/**
 *
 */
public class MetricsHistoryHandler extends RequestHandlerBase implements PermissionNameProvider, Closeable {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final List<String> DEFAULT_CORE_COUNTERS = new ArrayList<String>() {{
    add("QUERY./select.requests");
    add("UPDATE./update.requests");
  }};
  public static final List<String> DEFAULT_CORE_GAUGES = new ArrayList<String>() {{
    add("INDEX.sizeInBytes");
  }};
  public static final List<String> DEFAULT_NODE_GAUGES = new ArrayList<String>() {{
    add("CONTAINER.fs.coreRoot.usableSpace");
  }};
  public static final List<String> DEFAULT_JVM_GAUGES = new ArrayList<String>() {{
    add("memory.heap.used");
    add("os.processCpuLoad");
    add("os.systemLoadAverage");
  }};

  public static final String NUM_SHARDS_KEY = "numShards";
  public static final String NUM_REPLICAS_KEY = "numReplicas";
  public static final String NUM_NODES_KEY = "numNodes";

  public static final List<String> DEFAULT_COLLECTION_GAUGES = new ArrayList<String>() {{
    add(NUM_SHARDS_KEY);
    add(NUM_REPLICAS_KEY);
  }};

  public static final String COLLECT_PERIOD_PROP = "collectPeriod";
  public static final String SYNC_PERIOD_PROP = "syncPeriod";
  public static final String ENABLE_PROP = "enable";
  public static final String ENABLE_REPLICAS_PROP = "enableReplicas";
  public static final String ENABLE_NODES_PROP = "enableNodes";

  public static final int DEFAULT_COLLECT_PERIOD = 60;
  public static final String URI_PREFIX = "solr:";

  private final SolrRrdBackendFactory factory;
  private final String nodeName;
  private final SolrClient solrClient;
  private final MetricsHandler metricsHandler;
  private final SolrCloudManager cloudManager;
  private final TimeSource timeSource;
  private final int collectPeriod;
  private final Map<String, List<String>> counters = new HashMap<>();
  private final Map<String, List<String>> gauges = new HashMap<>();

  private final Map<String, RrdDb> knownDbs = new ConcurrentHashMap<>();

  private ScheduledThreadPoolExecutor collectService;
  private boolean logMissingCollection = true;
  private boolean enable;
  private boolean enableReplicas;
  private boolean enableNodes;
  private String versionString;

  public MetricsHistoryHandler(String nodeName, MetricsHandler metricsHandler,
        SolrClient solrClient, SolrCloudManager cloudManager, Map<String, Object> pluginArgs) {

    Map<String, Object> args = new HashMap<>();
    // init from optional solr.xml config
    if (pluginArgs != null) {
      args.putAll(pluginArgs);
    }
    // override from ZK
    Map<String, Object> props = (Map<String, Object>)cloudManager.getClusterStateProvider()
        .getClusterProperty("metrics", Collections.emptyMap())
        .getOrDefault("history", Collections.emptyMap());
    args.putAll(props);

    this.nodeName = nodeName;
    this.enable = Boolean.parseBoolean(String.valueOf(args.getOrDefault(ENABLE_PROP, "true")));
    // default to false - don't collect local per-replica metrics
    this.enableReplicas = Boolean.parseBoolean(String.valueOf(args.getOrDefault(ENABLE_REPLICAS_PROP, "false")));
    this.enableNodes = Boolean.parseBoolean(String.valueOf(args.getOrDefault(ENABLE_NODES_PROP, "false")));
    this.collectPeriod = Integer.parseInt(String.valueOf(args.getOrDefault(COLLECT_PERIOD_PROP, DEFAULT_COLLECT_PERIOD)));
    int syncPeriod = Integer.parseInt(String.valueOf(args.getOrDefault(SYNC_PERIOD_PROP, SolrRrdBackendFactory.DEFAULT_SYNC_PERIOD)));

    factory = new SolrRrdBackendFactory(solrClient, CollectionAdminParams.SYSTEM_COLL,
            syncPeriod, cloudManager.getTimeSource());
    this.solrClient = solrClient;
    this.metricsHandler = metricsHandler;
    this.cloudManager = cloudManager;
    this.timeSource = cloudManager.getTimeSource();

    counters.put(Group.core.toString(), DEFAULT_CORE_COUNTERS);
    counters.put(Group.node.toString(), Collections.emptyList());
    counters.put(Group.jvm.toString(), Collections.emptyList());
    counters.put(Group.collection.toString(), Collections.emptyList());
    gauges.put(Group.core.toString(), DEFAULT_CORE_GAUGES);
    gauges.put(Group.node.toString(), DEFAULT_NODE_GAUGES);
    gauges.put(Group.jvm.toString(), DEFAULT_JVM_GAUGES);
    gauges.put(Group.collection.toString(), DEFAULT_COLLECTION_GAUGES);

    versionString = this.getClass().getPackage().getImplementationVersion();
    if (versionString == null) {
      versionString = "?.?.?";
    }
    if (versionString.length() > 24) {
      versionString = versionString.substring(0, 24) + "...";
    }

    if (enable) {
      collectService = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1,
          new DefaultSolrThreadFactory("MetricsHistoryHandler"));
      collectService.setRemoveOnCancelPolicy(true);
      collectService.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
      collectService.scheduleWithFixedDelay(() -> collectMetrics(),
          timeSource.convertDelay(TimeUnit.SECONDS, collectPeriod, TimeUnit.MILLISECONDS),
          timeSource.convertDelay(TimeUnit.SECONDS, collectPeriod, TimeUnit.MILLISECONDS),
          TimeUnit.MILLISECONDS);
    }
  }

  public SolrClient getSolrClient() {
    return solrClient;
  }

  public void removeHistory(String registry) throws IOException {
    registry = SolrMetricManager.overridableRegistryName(registry);
    knownDbs.remove(registry);
    factory.remove(registry);
  }

  @VisibleForTesting
  public SolrRrdBackendFactory getFactory() {
    return factory;
  }

  private boolean isOverseerLeader() {
    ZkNodeProps props = null;
    try {
      VersionedData data = cloudManager.getDistribStateManager().getData(
          Overseer.OVERSEER_ELECT + "/leader");
      if (data != null && data.getData() != null) {
        props = ZkNodeProps.load(data.getData());
      }
    } catch (KeeperException | IOException | NoSuchElementException e) {
      log.warn("Could not obtain overseer's address, skipping.", e);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    if (props == null) {
      return false;
    }
    String oid = props.getStr(ID);
    if (oid == null) {
      return false;
    }
    String[] ids = oid.split("-");
    if (ids.length != 3) { // unknown format
      log.warn("Unknown format of leader id, skipping: " + oid);
      return false;
    }
    return nodeName.equals(ids[1]);
  }

  private void collectMetrics() {
    log.debug("-- collectMetrics");
    // check that .system exists
    try {
      if (cloudManager.isClosed() || Thread.interrupted()) {
        return;
      }
      ClusterState clusterState = cloudManager.getClusterStateProvider().getClusterState();
      DocCollection systemColl = clusterState.getCollectionOrNull(CollectionAdminParams.SYSTEM_COLL);
      if (systemColl == null) {
        if (logMissingCollection) {
          log.warn("Missing " + CollectionAdminParams.SYSTEM_COLL + ", skipping metrics collection");
          logMissingCollection = false;
        }
        return;
      } else {
        boolean ready = false;
        for (Replica r : systemColl.getReplicas()) {
          if (r.isActive(clusterState.getLiveNodes())) {
            ready = true;
            break;
          }
        }
        if (!ready) {
          log.debug(CollectionAdminParams.SYSTEM_COLL + " not ready yet...");
          return;
        }
      }
    } catch (Exception e) {
      log.warn("Error getting cluster state, skipping metrics collection", e);
      return;
    }
    logMissingCollection = true;
    // get metrics
    collectLocalReplicaMetrics();
    collectGlobalMetrics();
  }

  private void collectLocalReplicaMetrics() {
    List<Group> groups = new ArrayList<>();
    if (enableNodes) {
      groups.add(Group.jvm);
      groups.add(Group.node);
    }
    if (enableReplicas) {
      groups.add(Group.core);
    }
    for (Group group : groups) {
      if (Thread.interrupted()) {
        return;
      }
      log.debug("--  collecting local " + group + "...");
      ModifiableSolrParams params = new ModifiableSolrParams();
      params.add(MetricsHandler.GROUP_PARAM, group.toString());
      params.add(MetricsHandler.COMPACT_PARAM, "true");
      counters.get(group.toString()).forEach(c -> params.add(MetricsHandler.PREFIX_PARAM, c));
      gauges.get(group.toString()).forEach(c -> params.add(MetricsHandler.PREFIX_PARAM, c));
      AtomicReference<Object> result = new AtomicReference<>();
      try {
        metricsHandler.handleRequest(params, (k, v) -> {
          if (k.equals("metrics")) {
            result.set(v);
          }
        });
        NamedList nl = (NamedList)result.get();
        if (nl != null) {
          for (Iterator<Map.Entry<String, Object>> it = nl.iterator(); it.hasNext(); ) {
            Map.Entry<String, Object> entry = it.next();
            String registry = entry.getKey();
            if (group != Group.core) { // add nodeName prefix
              registry = registry + "." + nodeName;
            }

            RrdDb db = getOrCreateDb(registry, group);
            if (db == null) {
              continue;
            }
            // set the timestamp
            Sample s = db.createSample(TimeUnit.SECONDS.convert(timeSource.getEpochTimeNs(), TimeUnit.NANOSECONDS));
            NamedList<Object> values = (NamedList<Object>)entry.getValue();
            AtomicBoolean dirty = new AtomicBoolean(false);
            counters.get(group.toString()).forEach(c -> {
              Number val = (Number)values.get(c);
              if (val != null) {
                dirty.set(true);
                s.setValue(c, val.doubleValue());
              }
            });
            gauges.get(group.toString()).forEach(c -> {
              Number val = (Number)values.get(c);
              if (val != null) {
                dirty.set(true);
                s.setValue(c, val.doubleValue());
              }
            });
            if (dirty.get()) {
              s.update();
            }
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private void collectGlobalMetrics() {
    if (!isOverseerLeader()) {
      return;
    }
    Set<String> nodes = new HashSet<>(cloudManager.getClusterStateProvider().getLiveNodes());
    NodeStateProvider nodeStateProvider = cloudManager.getNodeStateProvider();
    Set<String> collTags = new HashSet<>();
    collTags.addAll(counters.get(Group.core.toString()));
    collTags.addAll(gauges.get(Group.core.toString()));

    Set<String> nodeTags = new HashSet<>();
    String nodePrefix = "metrics:" + SolrMetricManager.getRegistryName(Group.node) + ":";
    counters.get(Group.node.toString()).forEach(name -> {
      nodeTags.add(nodePrefix + name);
    });
    gauges.get(Group.node.toString()).forEach(name -> {
      nodeTags.add(nodePrefix + name);
    });
    String jvmPrefix = "metrics:" + SolrMetricManager.getRegistryName(Group.jvm) + ":";
    counters.get(Group.jvm.toString()).forEach(name -> {
      nodeTags.add(jvmPrefix + name);
    });
    gauges.get(Group.jvm.toString()).forEach(name -> {
      nodeTags.add(jvmPrefix + name);
    });

    // per-registry totals
    // XXX at the moment the type of metrics that we collect allows
    // adding all partial values. At some point it may be necessary to implement
    // other aggregation functions.
    // group : registry : name : value
    Map<Group, Map<String, Map<String, Number>>> totals = new HashMap<>();

    // collect and aggregate per-collection totals
    for (String node : nodes) {
      if (cloudManager.isClosed() || Thread.interrupted()) {
        return;
      }
      // add core-level stats
      Map<String, Map<String, List<ReplicaInfo>>> infos = nodeStateProvider.getReplicaInfo(node, collTags);
      infos.forEach((coll, shards) -> {
        shards.forEach((sh, replicas) -> {
          String registry = SolrMetricManager.getRegistryName(Group.collection, coll);
          Map<String, Number> perReg = totals
              .computeIfAbsent(Group.collection, g -> new HashMap<>())
              .computeIfAbsent(registry, r -> new HashMap<>());
          replicas.forEach(ri -> {
            collTags.forEach(tag -> {
              double value = ((Number)ri.getVariable(tag, 0.0)).doubleValue();
              // TODO: fix this when Suggestion.Condition.DISK_IDX uses proper conversion
              if (tag.contains(Suggestion.coreidxsize)) {
                value = value * 1024.0 * 1024.0 * 1024.0;
              }
              DoubleAdder adder = (DoubleAdder)perReg.computeIfAbsent(tag, t -> new DoubleAdder());
              adder.add(value);
            });
          });
        });
      });
      // add node-level stats
      Map<String, Object> nodeValues = nodeStateProvider.getNodeValues(node, nodeTags);
      for (Group g : Arrays.asList(Group.node, Group.jvm)) {
        String registry = SolrMetricManager.getRegistryName(g);
        Map<String, Number> perReg = totals
            .computeIfAbsent(g, gr -> new HashMap<>())
            .computeIfAbsent(registry, r -> new HashMap<>());
        Set<String> names = new HashSet<>();
        names.addAll(counters.get(g.toString()));
        names.addAll(gauges.get(g.toString()));
        names.forEach(name -> {
          String tag = "metrics:" + registry + ":" + name;
          double value = ((Number)nodeValues.getOrDefault(tag, 0.0)).doubleValue();
          DoubleAdder adder = (DoubleAdder)perReg.computeIfAbsent(name, t -> new DoubleAdder());
          adder.add(value);
        });
      }
    }

    // add numNodes
    String nodeReg = SolrMetricManager.getRegistryName(Group.node);
    Map<String, Number> perNodeReg = totals
        .computeIfAbsent(Group.node, gr -> new HashMap<>())
        .computeIfAbsent(nodeReg, r -> new HashMap<>());
    perNodeReg.put(NUM_NODES_KEY, nodes.size());

    // add some global collection-level stats
    try {
      ClusterState state = cloudManager.getClusterStateProvider().getClusterState();
      state.forEachCollection(coll -> {
        String registry = SolrMetricManager.getRegistryName(Group.collection, coll.getName());
        Map<String, Number> perReg = totals
            .computeIfAbsent(Group.collection, g -> new HashMap<>())
            .computeIfAbsent(registry, r -> new HashMap<>());
        Collection<Slice> slices = coll.getActiveSlices();
        perReg.put(NUM_SHARDS_KEY, slices.size());
        DoubleAdder numActiveReplicas = new DoubleAdder();
        slices.forEach(s -> {
          s.forEach(r -> {
            if (r.isActive(state.getLiveNodes())) {
              numActiveReplicas.add(1.0);
            }
          });
        });
        perReg.put(NUM_REPLICAS_KEY, numActiveReplicas);
      });
    } catch (IOException e) {
      log.warn("Exception getting cluster state", e);
    }

    // now update the db-s
    totals.forEach((group, perGroup) -> {
      perGroup.forEach((reg, perReg) -> {
        RrdDb db = getOrCreateDb(reg, group);
        if (db == null) {
          return;
        }
        try {
          // set the timestamp
          Sample s = db.createSample(TimeUnit.SECONDS.convert(timeSource.getEpochTimeNs(), TimeUnit.NANOSECONDS));
          AtomicBoolean dirty = new AtomicBoolean(false);
          List<Group> groups = new ArrayList<>();
          groups.add(group);
          if (group == Group.collection) {
            groups.add(Group.core);
          }
          for (Group g : groups) {
            counters.get(g.toString()).forEach(c -> {
              Number val = perReg.get(c);
              if (val != null) {
                dirty.set(true);
                s.setValue(c, val.doubleValue());
              }
            });
            gauges.get(g.toString()).forEach(c -> {
              Number val = perReg.get(c);
              if (val != null) {
                dirty.set(true);
                s.setValue(c, val.doubleValue());
              }
            });
          }
          if (dirty.get()) {
            s.update();
          }
        } catch (Exception e) {
        }
      });
    });
  }

  private RrdDef createDef(String registry, Group group) {
    registry = SolrMetricManager.overridableRegistryName(registry);

    // base sampling period is collectPeriod - samples more frequent than
    // that will be dropped, samples less frequent will be interpolated
    RrdDef def = new RrdDef(URI_PREFIX + registry, collectPeriod);
    // set the start time early enough so that the first sample is always later
    // than the start of the archive
    def.setStartTime(TimeUnit.SECONDS.convert(timeSource.getEpochTimeNs(), TimeUnit.NANOSECONDS) - def.getStep());

    // add datasources
    List<Group> groups = new ArrayList<>();
    groups.add(group);
    if (group == Group.collection) {
      groups.add(Group.core);
    }
    for (Group g : groups) {
      // use NaN when more than 1 sample is missing
      counters.get(g.toString()).forEach(name ->
          def.addDatasource(name, DsType.COUNTER, collectPeriod * 2, Double.NaN, Double.NaN));
      gauges.get(g.toString()).forEach(name ->
          def.addDatasource(name, DsType.GAUGE, collectPeriod * 2, Double.NaN, Double.NaN));
    }
    if (groups.contains(Group.node)) {
      // add nomNodes gauge
      def.addDatasource(NUM_NODES_KEY, DsType.GAUGE, collectPeriod * 2, Double.NaN, Double.NaN);
    }

    // add archives

    // use AVERAGE consolidation,
    // use NaN when >50% samples are missing
    def.addArchive(ConsolFun.AVERAGE, 0.5, 1, 240); // 4 hours
    def.addArchive(ConsolFun.AVERAGE, 0.5, 10, 288); // 48 hours
    def.addArchive(ConsolFun.AVERAGE, 0.5, 60, 336); // 2 weeks
    def.addArchive(ConsolFun.AVERAGE, 0.5, 240, 180); // 2 months
    def.addArchive(ConsolFun.AVERAGE, 0.5, 1440, 365); // 1 year
    return def;
  }

  private RrdDb getOrCreateDb(String registry, Group group) {
    RrdDb db = knownDbs.computeIfAbsent(registry, r -> {
      RrdDef def = createDef(r, group);
      try {
        RrdDb newDb = new RrdDb(def, factory);
        return newDb;
      } catch (IOException e) {
        return null;
      }
    });
    return db;
  }

  @Override
  public void close() {
    log.debug("Closing " + hashCode());
    if (collectService != null) {
      collectService.shutdownNow();
    }
    if (factory != null) {
      factory.close();
    }
    knownDbs.clear();
  }

  public enum Cmd {
    LIST, STATUS, GET, DELETE;

    static final Map<String, Cmd> actions = Collections.unmodifiableMap(
        Stream.of(Cmd.values())
            .collect(toMap(Cmd::toLower, Function.identity())));

    public static Cmd get(String p) {
      return p == null ? null : actions.get(p.toLowerCase(Locale.ROOT));
    }

    public String toLower() {
      return toString().toLowerCase(Locale.ROOT);
    }
  }

  public enum Format {
    LIST, STRING, GRAPH;

    static final Map<String, Format> formats = Collections.unmodifiableMap(
        Stream.of(Format.values())
            .collect(toMap(Format::toLower, Function.identity())));

    public static Format get(String p) {
      return p == null ? null : formats.get(p.toLowerCase(Locale.ROOT));
    }

    public String toLower() {
      return toString().toLowerCase(Locale.ROOT);
    }
  }


  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    String actionStr = req.getParams().get(CommonParams.ACTION);
    if (actionStr == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "'action' is a required param");
    }
    Cmd cmd = Cmd.get(actionStr);
    if (cmd == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "unknown 'action' param '" + actionStr + "', supported actions: " + Cmd.values());
    }
    Object res = null;
    switch (cmd) {
      case LIST:
        int rows = req.getParams().getInt(CommonParams.ROWS, SolrRrdBackendFactory.DEFAULT_MAX_DBS);
        res = factory.list(rows);
        break;
      case GET:
        String name = req.getParams().get(CommonParams.NAME);
        if (name == null) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "'name' is a required param");
        }
        String[] dsNames = req.getParams().getParams("ds");
        String formatStr = req.getParams().get("format", Format.LIST.toString());
        Format format = Format.get(formatStr);
        if (format == null) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "unknown 'format' param '" + formatStr + "', supported formats: " + Format.values());
        }
        if (!factory.exists(name)) {
          rsp.add("error", "'" + name + "' doesn't exist");
        } else {
          // get a throwaway copy (safe to close and discard)
          RrdDb db = new RrdDb(URI_PREFIX + name, true, factory);
          res = new NamedList<>();
          NamedList<Object> data = new NamedList<>();
          data.add("data", getDbData(db, dsNames, format, req.getParams()));
          ((NamedList)res).add(name, data);
          db.close();
        }
        break;
      case STATUS:
        name = req.getParams().get(CommonParams.NAME);
        if (name == null) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "'name' is a required param");
        }
        if (!factory.exists(name)) {
          rsp.add("error", "'" + name + "' doesn't exist");
        } else {
          // get a throwaway copy (safe to close and discard)
          RrdDb db = new RrdDb(URI_PREFIX + name, true, factory);
          NamedList<Object> map = new NamedList<>();
          NamedList<Object> status = new NamedList<>();
          status.add("status", getDbStatus(db));
          map.add(name, status);
          db.close();
          res = map;
        }
        break;
      case DELETE:
        name = req.getParams().get(CommonParams.NAME);
        if (name == null) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "'name' is a required param");
        }
        if (name.equalsIgnoreCase("all") || name.equals("*")) {
          factory.removeAll();
        } else {
          factory.remove(name);
        }
        rsp.add("success", "ok");
        break;
    }
    if (res != null) {
      rsp.add("metrics", res);
    }
  }

  private NamedList<Object> getDbStatus(RrdDb db) throws IOException {
    NamedList<Object> res = new SimpleOrderedMap<>();
    res.add("lastModified", db.getLastUpdateTime());
    RrdDef def = db.getRrdDef();
    res.add("step", def.getStep());
    res.add("datasourceCount", db.getDsCount());
    res.add("archiveCount", db.getArcCount());
    res.add("datasourceNames", Arrays.asList(db.getDsNames()));
    List<Object> dss = new ArrayList<>(db.getDsCount());
    res.add("datasources", dss);
    for (DsDef dsDef : def.getDsDefs()) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("datasource", dsDef.dump());
      Datasource ds = db.getDatasource(dsDef.getDsName());
      map.put("lastValue", ds.getLastValue());
      dss.add(map);
    }
    List<Object> archives = new ArrayList<>(db.getArcCount());
    res.add("archives", archives);
    ArcDef[] arcDefs = def.getArcDefs();
    for (int i = 0; i < db.getArcCount(); i++) {
      Archive a = db.getArchive(i);
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("archive", arcDefs[i].dump());
      map.put("steps", a.getSteps());
      map.put("consolFun", a.getConsolFun().name());
      map.put("xff", a.getXff());
      map.put("startTime", a.getStartTime());
      map.put("endTime", a.getEndTime());
      map.put("rows", a.getRows());
      archives.add(map);
    }

    return res;
  }

  private NamedList<Object> getDbData(RrdDb db, String[] dsNames, Format format, SolrParams params) throws IOException {
    NamedList<Object> res = new SimpleOrderedMap<>();
    if (dsNames == null || dsNames.length == 0) {
      dsNames = db.getDsNames();
    }
    StringBuilder str = new StringBuilder();
    RrdDef def = db.getRrdDef();
    ArcDef[] arcDefs = def.getArcDefs();
    for (ArcDef arcDef : arcDefs) {
      SimpleOrderedMap map = new SimpleOrderedMap();
      res.add(arcDef.dump(), map);
      Archive a = db.getArchive(arcDef.getConsolFun(), arcDef.getSteps());
      // startTime / endTime, arcStep are in seconds
      FetchRequest fr = db.createFetchRequest(arcDef.getConsolFun(),
          a.getStartTime() - a.getArcStep(),
          a.getEndTime() + a.getArcStep());
      FetchData fd = fr.fetchData();
      if (format != Format.GRAPH) {
        // add timestamps separately from values
        long[] timestamps = fd.getTimestamps();
        str.setLength(0);
        for (int i = 0; i < timestamps.length; i++) {
          if (format == Format.LIST) {
            map.add("timestamps", timestamps[i]);
          } else {
            if (i > 0) {
              str.append('\n');
            }
            str.append(String.valueOf(timestamps[i]));
          }
        }
        if (format == Format.STRING) {
          map.add("timestamps", str.toString());
        }
      }
      SimpleOrderedMap values = new SimpleOrderedMap();
      map.add("values", values);
      for (String name : dsNames) {
        double[] vals = fd.getValues(name);
        switch (format) {
          case GRAPH:
            RrdGraphDef graphDef = new RrdGraphDef();
            graphDef.setTitle(name);
            graphDef.datasource(name, fd);
            graphDef.setStartTime(a.getStartTime() - a.getArcStep());
            graphDef.setEndTime(a.getEndTime() + a.getArcStep());
            graphDef.setPoolUsed(false);
            graphDef.setAltAutoscale(true);
            graphDef.setAltYGrid(true);
            graphDef.setAltYMrtg(true);
            graphDef.setSignature("Apache Solr " + versionString);
            graphDef.setNoLegend(true);
            graphDef.setAntiAliasing(true);
            graphDef.setTextAntiAliasing(true);
            graphDef.setWidth(500);
            graphDef.setHeight(175);
            graphDef.setTimeZone(TimeZone.getDefault());
            graphDef.setLocale(Locale.getDefault());
            // redraw immediately
            graphDef.setLazy(false);
            // area with a border
            graphDef.area(name, new Color(0xffb860), null);
            graphDef.line(name, Color.RED, null, 1.0f);
            RrdGraph graph = new RrdGraph(graphDef);
            BufferedImage bi = new BufferedImage(
                graph.getRrdGraphInfo().getWidth(),
                graph.getRrdGraphInfo().getHeight(),
                BufferedImage.TYPE_INT_RGB);
            graph.render(bi.getGraphics());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", baos);
            values.add(name, Base64.byteArrayToBase64(baos.toByteArray()));
            break;
          case STRING:
            str.setLength(0);
            for (int i = 0; i < vals.length; i++) {
              if (i > 0) {
                str.append('\n');
              }
              str.append(String.valueOf(vals[i]));
            }
            values.add(name, str.toString());
            break;
          case LIST:
            for (int i = 0; i < vals.length; i++) {
              values.add(name, vals[i]);
            }
            break;
        }
      }
    }
    return res;
  }

  @Override
  public String getDescription() {
    return "A handler for metrics history";
  }

  @Override
  public Name getPermissionName(AuthorizationContext request) {
    return Name.METRICS_HISTORY_READ_PERM;
  }

  @Override
  public Boolean registerV2() {
    return Boolean.TRUE;
  }

  @Override
  public Collection<Api> getApis() {
    return ApiBag.wrapRequestHandlers(this, "metrics.history");
  }

}