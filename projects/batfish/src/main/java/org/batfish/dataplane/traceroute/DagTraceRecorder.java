package org.batfish.dataplane.traceroute;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.batfish.common.util.BatfishObjectMapper;
import org.batfish.datamodel.Flow;
import org.batfish.datamodel.flow.Hop;

/**
 * {@link TraceRecorder} that compresses traces into a {@link TraceDag} and allows partial traces to
 * be recorded by reusing already-computed subgraphs.
 */
@ParametersAreNonnullByDefault
public class DagTraceRecorder implements TraceRecorder {
  private final @Nonnull Flow _flow;

  DagTraceRecorder(@Nonnull Flow flow) {
    _flow = flow;
  }

  /**
   * The key used to lookup Nodes in a TraceDag.
   *
   * <p>TODO: implement equals/hashCode for {@link Hop} instead of using JSON.
   */
  private static final class NodeKey {
    private final @Nonnull Flow _initialFlow;
    private final @Nonnull String _hopJson;

    private NodeKey(Flow initialFlow, Hop hop) {
      _initialFlow = initialFlow;
      _hopJson = BatfishObjectMapper.writeStringRuntimeError(hop);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof NodeKey)) {
        return false;
      }
      NodeKey nodeKey = (NodeKey) o;
      return _initialFlow.equals(nodeKey._initialFlow) && _hopJson.equals(nodeKey._hopJson);
    }

    @Override
    public int hashCode() {
      return Objects.hash(_initialFlow, _hopJson);
    }
  }

  /** Traces are generated in DFS order, i.e. grouped by common prefix. */
  @VisibleForTesting
  final class NodeBuilder {
    private final @Nonnull NodeKey _key;
    final List<Breadcrumb> _nextHopBreadcrumbs;
    final HopInfo _hopInfo;
    final boolean _isFinalHop;
    @Nullable NodeBuilder _currentNextHopBuilder;
    final @Nullable List<Node> _nextHops;

    NodeBuilder(List<Breadcrumb> breadcrumbs, HopInfo hopInfo, NodeKey key) {
      _hopInfo = hopInfo;
      _key = key;

      @Nullable Breadcrumb visitedBreadcrumb = _hopInfo.getVisitedBreadcrumb();
      _nextHopBreadcrumbs =
          visitedBreadcrumb == null
              ? ImmutableList.copyOf(breadcrumbs)
              : ImmutableList.<Breadcrumb>builder()
                  .addAll(breadcrumbs)
                  .add(_hopInfo.getVisitedBreadcrumb())
                  .build();
      _isFinalHop = _hopInfo.getDisposition() != null;
      _nextHops = _isFinalHop ? null : new ArrayList<>();
    }

    boolean tryRecordPartialTrace(List<HopInfo> hops) {
      assert !_isFinalHop || hops.isEmpty();
      assert _isFinalHop == (_nextHops == null);
      if (hops.isEmpty()) {
        return _isFinalHop;
      }
      HopInfo nextHop = hops.get(0);
      if (_currentNextHopBuilder != null && _currentNextHopBuilder._hopInfo != nextHop) {
        _nextHops.add(_currentNextHopBuilder.build());
        _currentNextHopBuilder = null;
      }
      if (_currentNextHopBuilder == null) {
        NodeKey key = new NodeKey(nextHop.getInitialFlow(), nextHop.getHop());
        Node node = findMatchingNode(key, _nextHopBreadcrumbs);
        if (node != null) {
          if (!_nextHops.contains(node)) {
            _nextHops.add(node);
          }
          return true;
        }
        _currentNextHopBuilder = new NodeBuilder(_nextHopBreadcrumbs, nextHop, key);
      }

      assert _currentNextHopBuilder._hopInfo == nextHop;
      return _currentNextHopBuilder.tryRecordPartialTrace(hops.subList(1, hops.size()));
    }

    Node build() {
      Breadcrumb visitedBreadcrumb = _hopInfo.getVisitedBreadcrumb();
      Breadcrumb loopDetectedBreadcrumb = _hopInfo.getLoopDetectedBreadcrumb();

      if (_isFinalHop) {
        Set<Breadcrumb> requiredBreadcrumbs =
            loopDetectedBreadcrumb == null
                ? ImmutableSet.of()
                : ImmutableSet.of(loopDetectedBreadcrumb);
        Set<Breadcrumb> forbiddenBreadcrumbs =
            visitedBreadcrumb == null
                ? ImmutableSet.of()
                : ImmutableSet.of(_hopInfo.getVisitedBreadcrumb());
        Node node =
            new Node(_hopInfo, ImmutableList.of(), requiredBreadcrumbs, forbiddenBreadcrumbs);
        _nodeMap.put(_key, node);
        return node;
      } else {
        assert _nextHops != null : "_nextHops cannot be null if not the final hop";
        assert visitedBreadcrumb != null : "visitedBreadcrumb cannot be null if not the final hop";

        if (_currentNextHopBuilder != null) {
          _nextHops.add(_currentNextHopBuilder.build());
        }

        ImmutableSet.Builder<Breadcrumb> requiredBreadcrumbsBuilder = ImmutableSet.builder();
        ImmutableSet.Builder<Breadcrumb> forbiddenBreadcrumbsBuilder = ImmutableSet.builder();
        forbiddenBreadcrumbsBuilder.add(visitedBreadcrumb);
        for (Node nextHop : _nextHops) {
          assert !nextHop._forbiddenBreadcrumbs.contains(visitedBreadcrumb)
              : "Node's breadcrumb is forbidden by a successor";
          forbiddenBreadcrumbsBuilder.addAll(nextHop._forbiddenBreadcrumbs);
          requiredBreadcrumbsBuilder.addAll(
              nextHop._requiredBreadcrumbs.contains(visitedBreadcrumb)
                  ? Sets.difference(
                      nextHop._requiredBreadcrumbs, ImmutableSet.of(visitedBreadcrumb))
                  : nextHop._requiredBreadcrumbs);
        }
        ImmutableSet<Breadcrumb> requiredBreadcrumbs = requiredBreadcrumbsBuilder.build();
        ImmutableSet<Breadcrumb> forbiddenBreadcrumbs = forbiddenBreadcrumbsBuilder.build();
        assert !requiredBreadcrumbs.contains(visitedBreadcrumb) : "A node cannot require itself";
        Node node =
            new Node(
                _hopInfo,
                ImmutableList.copyOf(_nextHops),
                requiredBreadcrumbs,
                forbiddenBreadcrumbs);
        _nodeMap.put(_key, node);
        return node;
      }
    }
  }

  private static final class Node {
    private final HopInfo _hopInfo;
    private final List<Node> _successors;
    private final Set<Breadcrumb> _requiredBreadcrumbs;
    private final Set<Breadcrumb> _forbiddenBreadcrumbs;

    private Node(
        HopInfo hopInfo,
        List<Node> successors,
        Set<Breadcrumb> requiredBreadcrumbs,
        Set<Breadcrumb> forbiddenBreadcrumbs) {
      _hopInfo = hopInfo;
      _successors = ImmutableList.copyOf(successors);
      _requiredBreadcrumbs = ImmutableSet.copyOf(requiredBreadcrumbs);
      _forbiddenBreadcrumbs = ImmutableSet.copyOf(forbiddenBreadcrumbs);
    }

    /**
     * sessionAction will be non-null for checking if we can reuse traces in traceroute. it can be
     * null when de-duping a node after creation.
     */
    boolean matches(List<Breadcrumb> breadcrumbs) {
      return breadcrumbs.containsAll(_requiredBreadcrumbs)
          && _forbiddenBreadcrumbs.stream().noneMatch(breadcrumbs::contains);
    }

    /** Convert a {@link Node} to a {@link TraceDag.Node}. */
    private int buildTraceDagNode(Map<Node, Integer> cache, List<TraceDag.Node> traceDagNodes) {
      @Nullable Integer nodeId = cache.get(this);
      if (nodeId != null) {
        return nodeId;
      }

      List<Integer> successorIds =
          _successors.stream()
              .map(successor -> successor.buildTraceDagNode(cache, traceDagNodes))
              .collect(ImmutableList.toImmutableList());

      HopInfo hopInfo = _hopInfo;
      nodeId = traceDagNodes.size();
      traceDagNodes.add(
          new TraceDag.Node(
              hopInfo.getHop(),
              hopInfo.getFirewallSessionTraceInfo(),
              hopInfo.getDisposition(),
              hopInfo.getReturnFlow(),
              successorIds));
      return nodeId;
    }
  }

  // indices are aligned
  private final List<Node> _roots = new ArrayList<>();
  private final Multimap<NodeKey, Node> _nodeMap = HashMultimap.create();
  private NodeBuilder _rootBuilder = null;
  private @Nullable TraceDag _builtTraceDag = null;

  private @Nullable Node findMatchingNode(NodeKey key, List<Breadcrumb> breadcrumbs) {
    Collection<Node> nodes = _nodeMap.get(key);
    if (nodes.isEmpty()) {
      return null;
    }
    List<Node> matches =
        nodes.stream().filter(node -> node.matches(breadcrumbs)).collect(Collectors.toList());
    checkState(matches.size() < 2, "Found 2 matching Trace nodes");
    return matches.isEmpty() ? null : matches.get(0);
  }

  @Override
  public boolean tryRecordPartialTrace(List<HopInfo> hops) {
    checkState(_builtTraceDag == null, "Cannot add traces after the Dag has been built");
    HopInfo rootHop = hops.get(0);
    if (_rootBuilder != null && _rootBuilder._hopInfo != rootHop) {
      buildRoot();
    }
    if (_rootBuilder == null) {
      _rootBuilder =
          new NodeBuilder(ImmutableList.of(), rootHop, new NodeKey(_flow, rootHop.getHop()));
    }
    return _rootBuilder.tryRecordPartialTrace(hops.subList(1, hops.size()));
  }

  @Override
  public void recordTrace(List<HopInfo> hops) {
    checkState(tryRecordPartialTrace(hops), "Failed to record a complete trace.");
  }

  private void buildRoot() {
    _roots.add(_rootBuilder.build());
    _rootBuilder = null;
  }

  public TraceDag build() {
    if (_builtTraceDag != null) {
      return _builtTraceDag;
    }
    if (_rootBuilder != null) {
      buildRoot();
    }
    Map<Node, Integer> nodeIds = new HashMap<>();
    List<TraceDag.Node> dagNodes = new ArrayList<>(_nodeMap.size());
    List<Integer> rootIds =
        _roots.stream()
            .map(root -> root.buildTraceDagNode(nodeIds, dagNodes))
            .collect(ImmutableList.toImmutableList());
    return _builtTraceDag = new TraceDag(dagNodes, rootIds);
  }
}
