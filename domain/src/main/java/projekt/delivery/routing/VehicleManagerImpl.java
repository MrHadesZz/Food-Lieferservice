package projekt.delivery.routing;

import org.jetbrains.annotations.Nullable;
import projekt.base.Location;
import projekt.delivery.event.Event;
import projekt.delivery.event.EventBus;
import projekt.delivery.event.SpawnEvent;

import java.util.*;

import static org.tudalgo.algoutils.student.Student.crash;

class VehicleManagerImpl implements VehicleManager {

    final Map<Region.Node, OccupiedNodeImpl<? extends Region.Node>> occupiedNodes;
    final Map<Region.Edge, OccupiedEdgeImpl> occupiedEdges;
    private final Region region;
    private final PathCalculator pathCalculator;
    private final List<VehicleImpl> vehiclesToSpawn = new ArrayList<>();
    private final List<VehicleImpl> vehicles = new ArrayList<>();
    private final Collection<Vehicle> unmodifiableVehicles = Collections.unmodifiableCollection(vehicles);
    private final EventBus eventBus = new EventBus();

    VehicleManagerImpl(
        Region region,
        PathCalculator pathCalculator
    ) {
        this.region = region;
        this.pathCalculator = pathCalculator;
        occupiedNodes = toOccupiedNodes(region.getNodes());
        occupiedEdges = toOccupiedEdges(region.getEdges());
    }

    private Map<Region.Node, OccupiedNodeImpl<? extends Region.Node>> toOccupiedNodes(Collection<Region.Node> nodes) {
        Map<Region.Node, OccupiedNodeImpl<? extends Region.Node>> occupiedNodes = new HashMap<>();
        for (Region.Node node : nodes) {
            if (node instanceof Region.Restaurant) {
                occupiedNodes.put(node, new OccupiedRestaurantImpl((Region.Restaurant) node, this));
            } else if (node instanceof Region.Neighborhood) {
                occupiedNodes.put(node, new OccupiedNeighborhoodImpl((Region.Neighborhood) node, this));
            } else {
                occupiedNodes.put(node, new OccupiedNodeImpl<>(node, this));
            }
        }
        return Collections.unmodifiableMap(occupiedNodes);
    }

    private Map<Region.Edge, OccupiedEdgeImpl> toOccupiedEdges(Collection<Region.Edge> edges) {
        Map<Region.Edge, OccupiedEdgeImpl> occupiedEdges = new HashMap<>();
        for (Region.Edge edge : edges) {
            occupiedEdges.put(edge, new OccupiedEdgeImpl(edge, this));
        }
        return Collections.unmodifiableMap(occupiedEdges);
    }

    private Set<AbstractOccupied<?>> getAllOccupied() {
        Set<AbstractOccupied<?>> allOccupied = new HashSet<>(occupiedNodes.values());
        allOccupied.addAll(occupiedEdges.values());
        return Collections.unmodifiableSet(allOccupied);
    }

    private OccupiedNodeImpl<? extends Region.Node> getOccupiedNode(Location location) {
        return occupiedNodes.values().stream()
            .filter(node -> node.getComponent().getLocation().equals(location))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Could not find node with given predicate"));
    }

    @Override
    public Region getRegion() {
        return region;
    }

    @Override
    public PathCalculator getPathCalculator() {
        return pathCalculator;
    }

    @Override
    public Collection<Vehicle> getVehicles() {
        return unmodifiableVehicles;
    }

    @Override
    public Collection<Vehicle> getAllVehicles() {
        Collection<Vehicle> allVehicles = new ArrayList<>(getVehicles());
        allVehicles.addAll(vehiclesToSpawn);
        return allVehicles;
    }

    @Override
    public <C extends Region.Component<C>> AbstractOccupied<C> getOccupied(C component) {
        Objects.requireNonNull(component, "Component is null!");
        if (component instanceof Region.Node) {
            final @Nullable AbstractOccupied<C> result = (AbstractOccupied<C>) occupiedNodes.get(component);
            if (result == null) {
                throw new IllegalArgumentException("Could not find occupied node for " + component);
            }
            return result;
        } else if (component instanceof Region.Edge) {
            final @Nullable AbstractOccupied<C> result = (AbstractOccupied<C>) occupiedEdges.get(component);
            if (result == null) {
                throw new IllegalArgumentException("Could not find occupied edge for " + component);
            }
            return result;
        }
        throw new IllegalArgumentException("Component is not of recognized subtype: " + component.getClass().getName());
    }

    @Override
    public List<OccupiedRestaurant> getOccupiedRestaurants() {
        return occupiedNodes.values().stream()
            .filter(OccupiedRestaurant.class::isInstance)
            .map(OccupiedRestaurant.class::cast)
            .toList();
    }

    @Override
    public OccupiedRestaurant getOccupiedRestaurant(Region.Node node) {
        if (node == null) {
            throw new NullPointerException("Node is null!");
        }
        Occupied<? extends Region.Node> occupied = occupiedNodes.get(node);
        if (occupied == null || !(occupied instanceof OccupiedRestaurant)) {
            throw new IllegalArgumentException("Node " + node + " is not a restaurant");
        }
        return (OccupiedRestaurant) occupied;
    }

    @Override
    public Collection<OccupiedNeighborhood> getOccupiedNeighborhoods() {
        return occupiedNodes.values().stream()
            .filter(OccupiedNeighborhood.class::isInstance)
            .map(OccupiedNeighborhood.class::cast)
            .toList();
    }

    @Override
    public OccupiedNeighborhood getOccupiedNeighborhood(Region.Node node) {
        if (node == null) {
            throw new NullPointerException("Node is null!");
        }
        Occupied<? extends Region.Node> occupied = occupiedNodes.get(node);
        if (occupied == null || !(occupied instanceof OccupiedNeighborhood)) {
            throw new IllegalArgumentException("Node " + node + " is not a neighborhood");
        }
        return (OccupiedNeighborhood) occupied;
    }

    @Override
    public Collection<Occupied<? extends Region.Node>> getOccupiedNodes() {
        return Collections.unmodifiableCollection(occupiedNodes.values());
    }

    @Override
    public Collection<Occupied<? extends Region.Edge>> getOccupiedEdges() {
        return Collections.unmodifiableCollection(occupiedEdges.values());
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public List<Event> tick(long currentTick) {
        for (VehicleImpl vehicle : vehiclesToSpawn) {
            spawnVehicle(vehicle, currentTick);
        }
        vehiclesToSpawn.clear();
        // It is important that nodes are ticked before edges
        // This only works because edge ticking is idempotent
        // Otherwise, there may be two state changes in a single tick.
        // For example, a node tick may move a vehicle onto an edge.
        // Ticking this edge afterwards does not move the vehicle further along the edge
        // compared to a vehicle already on the edge.
        occupiedNodes.values().forEach(occupiedNode -> occupiedNode.tick(currentTick));
        occupiedEdges.values().forEach(occupiedEdge -> occupiedEdge.tick(currentTick));
        return eventBus.popEvents(currentTick);
    }

    public void reset() {
        for (AbstractOccupied<?> occupied : getAllOccupied()) {
            occupied.reset();
        }

        for (Vehicle vehicle : getAllVehicles()) {
            vehicle.reset();
        }

        vehiclesToSpawn.addAll(getVehicles().stream()
            .map(VehicleImpl.class::cast)
            .toList());

        vehicles.clear();
    }

    @SuppressWarnings("UnusedReturnValue")
    Vehicle addVehicle(
        Location startingLocation,
        double capacity
    ) {
        OccupiedNodeImpl<? extends Region.Node> occupied = getOccupiedNode(startingLocation);

        if (!(occupied instanceof OccupiedRestaurant)) {
            throw new IllegalArgumentException("Vehicles can only spawn at restaurants!");
        }

        final VehicleImpl vehicle = new VehicleImpl(
            vehicles.size() + vehiclesToSpawn.size(),
            capacity,
            this,
            (OccupiedRestaurant) occupied);
        vehiclesToSpawn.add(vehicle);
        vehicle.setOccupied(occupied);
        return vehicle;
    }

    private void spawnVehicle(VehicleImpl vehicle, long currentTick) {
        vehicles.add(vehicle);
        OccupiedRestaurantImpl warehouse = (OccupiedRestaurantImpl) vehicle.getOccupied();
        warehouse.vehicles.put(vehicle, new AbstractOccupied.VehicleStats(currentTick, null));
        getEventBus().queuePost(SpawnEvent.of(currentTick, vehicle, warehouse.getComponent()));
    }
}
