package projekt.delivery.routing;

import org.jetbrains.annotations.Nullable;
import projekt.base.DistanceCalculator;
import projekt.base.EuclideanDistanceCalculator;
import projekt.base.Location;

import java.util.*;

import static org.tudalgo.algoutils.student.Student.crash;

class RegionImpl implements Region {

    private final Map<Location, NodeImpl> nodes = new HashMap<>();
    private final Map<Location, Map<Location, EdgeImpl>> edges = new HashMap<>();
    private final List<EdgeImpl> allEdges = new ArrayList<>();
    private final DistanceCalculator distanceCalculator;

    /**
     * Creates a new, empty {@link RegionImpl} instance using a {@link EuclideanDistanceCalculator}.
     */
    public RegionImpl() {
        this(new EuclideanDistanceCalculator());
    }

    /**
     * Creates a new, empty {@link RegionImpl} instance using the given {@link DistanceCalculator}.
     */
    public RegionImpl(DistanceCalculator distanceCalculator) {
        this.distanceCalculator = distanceCalculator;
    }

    @Override
    public @Nullable Node getNode(Location location) {
        return nodes.get(location);
    }

    @Override
    public @Nullable Edge getEdge(Location locationA, Location locationB) {
        Edge edge = null;
        if (edges.containsKey(locationA) && edges.get(locationA).containsKey(locationB)) {
            edge = edges.get(locationA).get(locationB);
        } else if (edges.containsKey(locationB) && edges.get(locationB).containsKey(locationA)) {
            edge = edges.get(locationB).get(locationA);
        }
        return edge;
    }

    @Override
    public Collection<Node> getNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    @Override
    public Collection<Edge> getEdges() {
        return Collections.unmodifiableCollection(allEdges);
    }

    @Override
    public DistanceCalculator getDistanceCalculator() {
        return distanceCalculator;
    }

    /**
     * Adds the given {@link NodeImpl} to this {@link RegionImpl}.
     * @param node the {@link NodeImpl} to add.
     */
    void putNode(NodeImpl node) {
        if (node.getRegion() != this) {
            throw new IllegalArgumentException("Node " + node + " has incorrect region");
        }
        nodes.put(node.getLocation(), node);
    }

    /**
     * Adds the given {@link EdgeImpl} to this {@link RegionImpl}.
     * @param edge the {@link EdgeImpl} to add.
     */
    void putEdge(EdgeImpl edge) {
        Node nodeA = edge.getNodeA();
        Node nodeB = edge.getNodeB();
        if (nodeA == null) {
            throw new IllegalArgumentException("NodeA " + edge.getLocationA().toString() + " is not part of the region");
        } else if (nodeB == null) {
            throw new IllegalArgumentException("NodeB " + edge.getLocationB().toString() + " is not part of the region");
        }
        if (edge.getRegion() != this || nodeA.getRegion() != this || nodeB.getRegion() != this) {
            throw new IllegalArgumentException("Edge " + edge + " has incorrect region");
        }
        Location locationA = nodeA.getLocation();
        Location locationB = nodeB.getLocation();
        if (!edges.containsKey(locationA)) {
            edges.put(locationA, new HashMap<>());
        }
        edges.get(locationA).put(locationB, edge);
        allEdges.add(edge);
        allEdges.sort(null);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof RegionImpl other)) {
            return false;
        }
        return Objects.equals(nodes, other.nodes) && Objects.equals(edges, other.edges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, edges);
    }
}
