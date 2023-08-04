package projekt.delivery.service;

import projekt.base.TickInterval;
import projekt.delivery.event.ArrivedAtRestaurantEvent;
import projekt.delivery.event.Event;
import projekt.delivery.event.SpawnEvent;
import projekt.delivery.routing.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.tudalgo.algoutils.student.Student.crash;

public class OurDeliveryService extends AbstractDeliveryService {
    private final List<RestaurantManager> managers = new ArrayList<>();
    protected final List<ConfirmedOrder> pendingOrders = new ArrayList<>();

    public OurDeliveryService(VehicleManager vehicleManager) {
        super(vehicleManager);
    }

    @Override
    protected List<Event> tick(long currentTick, List<ConfirmedOrder> newOrders) {

        List<Event> events = vehicleManager.tick(currentTick);

        handleEvents(events);

        Map<RestaurantManager, List<ConfirmedOrder>> ordersForManager = new HashMap<>();

        // Group orders by manager
        for (ConfirmedOrder order : newOrders) {
            RestaurantManager manager = getResponsibleManager(order);
            if (!ordersForManager.containsKey(manager)) {
                ordersForManager.put(manager, new ArrayList<>());
            }
            ordersForManager.get(manager).add(order);
        }

        // Tick managers with their new orders
        for (RestaurantManager manager : managers) {
            manager.tick(currentTick, ordersForManager.getOrDefault(manager, List.of()));
        }

        distributeVehicles();

        return events;
    }


    /**
     * Tries to distribute unused vehicles from managers with more vehicles to managers with fewer vehicles.
     */
    private void distributeVehicles() {
        int unusedVehicles = 0;

        // Count unused vehicles
        for (RestaurantManager manager : managers) {
            unusedVehicles += manager.getUnusedVehicles().size();
        }

        // Calculate how many unused vehicles each manager should have
        int vehiclesPerManager = unusedVehicles / managers.size();

        for (RestaurantManager manager : managers) {

            // Calculate how many vehicles need to be moved
            int vehicleDiff = vehiclesPerManager - manager.getUnusedVehicles().size();

            // If the manager has enough vehicles, skip it
            if (vehicleDiff <= 0) {
                continue;
            }

            // Move vehicles from other managers to this manager
            for (RestaurantManager otherManager : managers) {

                if (otherManager == manager) {
                    continue;
                }

                // Move vehicles until the manager has enough vehicles or there are no more vehicles to move
                while (otherManager.getTotalAvailableVehicle().size() < vehiclesPerManager && vehicleDiff > 0) {
                    Vehicle vehicle = otherManager.getUnusedVehicles().get(0);
                    vehicle.moveQueued(manager.managed);
                    manager.addQueuedVehicle(vehicle);
                    otherManager.removeVehicle(vehicle);
                    vehicleDiff--;
                }
            }
        }
    }

    /**
     * Handles all Events created by the {@link VehicleManager}.
     *
     * @param events The events to handle.
     */
    private void handleEvents(List<Event> events) {

        // Add vehicles to the responsible manager when they arrive at a restaurant
        events.stream()
            .filter(ArrivedAtRestaurantEvent.class::isInstance)
            .map(ArrivedAtRestaurantEvent.class::cast)
            .forEach(event -> {
                RestaurantManager manager = managers.stream()
                    .filter(m -> m.getManaged().equals(event.getRestaurant().getComponent()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No responsible manager found for restaurant " + event.getRestaurant().getComponent()));
                manager.addVehicle(event.getVehicle());
            });

        // Add vehicles to the responsible manager when they are spawned
        events.stream()
            .filter(SpawnEvent.class::isInstance)
            .map(SpawnEvent.class::cast)
            .forEach(event -> {
                RestaurantManager manager = managers.stream()
                    .filter(m -> m.getManaged().equals(event.getNode()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No responsible manager found for restaurant " + event.getNode()));
                manager.addVehicle(event.getVehicle());
            });
    }

    /**
     * Returns the {@link RestaurantManager} responsible for the given {@link ConfirmedOrder}.
     *
     * @param order The order to get the responsible manager for.
     * @return The responsible manager.
     */
    private RestaurantManager getResponsibleManager(ConfirmedOrder order) {
        return managers.stream()
            .filter(manager -> manager.getManaged().equals(order.getRestaurant().getComponent()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No responsible manager found for order " + order));
    }

    /**
     * Creates a new {@link RestaurantManager} for each {@link Region.Restaurant} and adds it to the list of managers.
     */
    private void createManagers() {
        for (VehicleManager.OccupiedRestaurant restaurant : vehicleManager.getOccupiedRestaurants()) {
            managers.add(new RestaurantManager(restaurant.getComponent(), new ArrayList<>(restaurant.getVehicles()), vehicleManager.getPathCalculator()));
        }
    }

    @Override
    public List<ConfirmedOrder> getPendingOrders() {
        List<ConfirmedOrder> pendingOrders = new ArrayList<>();

        for (RestaurantManager manager : managers) {
            pendingOrders.addAll(manager.getPendingOrders());
        }

        return pendingOrders;
    }

    @Override
    public void reset() {
        super.reset();
        managers.clear();
        createManagers();
    }

    /**
     * A factory for {@link OurDeliveryService} instances.
     */
    public interface Factory extends DeliveryService.Factory {

        /**
         * Creates a new {@link OurDeliveryService} instance.
         *
         * @param vehicleManager The underlying {@link VehicleManager}.
         * @return The created {@link OurDeliveryService} instance.
         */
        @Override
        OurDeliveryService create(VehicleManager vehicleManager);
    }

    /**
     * A manager for a single {@link Region.Restaurant}.
     * It is responsible for managing the vehicles and the orders for a single restaurant.
     */
    private class RestaurantManager {

        private final Region.Restaurant managed;
        private final Map<Vehicle, List<RouteNode>> planedRoutes = new HashMap<>();
        private final Region region;
        private final PathCalculator pathCalculator;
        private final List<ConfirmedOrder> pendingOrders = new ArrayList<>();
        private final List<Vehicle> queuedVehicles = new ArrayList<>();

        /**
         * Creates a new {@link RestaurantManager} instance.
         *
         * @param managed           The {@link Region.Restaurant} this manager is responsible for.
         * @param availableVehicles The vehicles that are available for this manager.
         * @param pathCalculator    The {@link PathCalculator} to use.
         */
        public RestaurantManager(Region.Restaurant managed, List<Vehicle> availableVehicles, PathCalculator pathCalculator) {
            this.managed = managed;
            this.pathCalculator = pathCalculator;

            region = managed.getRegion();

            availableVehicles.forEach(this::addVehicle);
        }

        /**
         * Accepts a single order that arrives at the restaurant and tries to add it to the planned routes in the best possible way.
         *
         * @param order       The order to accept.
         * @param currentTick The current tick.
         */
        @SuppressWarnings("DuplicatedCode")
        public void acceptOrder(ConfirmedOrder order, long currentTick) {

            //Calculate all paths to the order location
            Map<Region.Node, Deque<Region.Node>> paths = pathCalculator.getAllPathsTo(region.getNode(order.getLocation()));

            Vehicle bestVehicle = null; //The vehicle that is best suited for the order
            List<RouteNode> bestNewRoute = null; //The new route that is best suited for the order

            // Check which vehicle would be best suited for the order
            for (Map.Entry<Vehicle, List<RouteNode>> plannedRoute : planedRoutes.entrySet()) {

                Vehicle responsibleVehicle = plannedRoute.getKey();
                List<RouteNode> route = plannedRoute.getValue();
                List<Region.Node> nodes = route.stream().map(RouteNode::node).toList();

                // Check if the capacity of the vehicle would be exceeded
                if (getWeight(route) + order.getWeight() > responsibleVehicle.getCapacity()) {
                    continue;
                }

                // if the current route is empty, calculate a new one
                if (route.isEmpty()) {
                    List<RouteNode> newRoute = pathCalculator.getPath(getManaged(), region.getNode(order.getLocation())).stream()
                        .map(node -> new RouteNode(node, new ArrayList<>()))
                        .collect(Collectors.toCollection(ArrayList::new));
                    newRoute.get(newRoute.size() - 1).orders.add(order);

                    // If the new route is better than the current best route, switch to it
                    switch (compareRoute(bestNewRoute, newRoute, order, currentTick)) {

                        // If the order would be delivered to early we will not add it to a planned route for now
                        case BREAK -> {
                            pendingOrders.add(order);
                            return;
                        }
                        // If the new route is better, switch to it
                        case SWITCH -> {
                            bestVehicle = responsibleVehicle;
                            bestNewRoute = newRoute;
                        }
                    }

                    // If the current route is empty we can skip the rest of the checks
                    continue;
                }

                // If the planned route is not empty, check if the order location is already on the route
                Optional<RouteNode> matchingNode = route.stream().filter(routeNode -> routeNode.node().getLocation().equals(order.getLocation())).findAny();

                if (matchingNode.isPresent()) { // If the order location is already on the route

                    //Copy the current route toa void modifying the original route
                    List<RouteNode> newRoute = copyRoute(route);

                    //Because the order location is already on the route, we can just add the order to the existing node
                    newRoute.get(newRoute.indexOf(matchingNode.get())).orders().add(order);

                    // If the new route is better than the current best route, switch to it
                    switch (compareRoute(bestNewRoute, newRoute, order, currentTick)) {

                        // If the order would be delivered to early we will not add it to a planned route for now
                        case BREAK -> {
                            pendingOrders.add(order);
                            return;
                        }
                        // If the new route is better, switch to it
                        case SWITCH -> {
                            bestVehicle = responsibleVehicle;
                            bestNewRoute = newRoute;
                        }
                    }

                    // If the order location is already on the route we can skip the rest of the checks
                    continue;
                }

                // If the order location is not on the route, check all possible attachment points
                for (Region.Node possibleAttachment : plannedRoute.getValue().stream().map(RouteNode::node).toList()) {

                    //Copy the current route toa void modifying the original route
                    List<RouteNode> newRoute = copyRoute(route);

                    //Calculate the path from the possible attachment point to the order location
                    List<RouteNode> routeToOrder = paths.get(possibleAttachment).stream()
                        .map(node -> new RouteNode(node, new ArrayList<>()))
                        .collect(Collectors.toCollection(ArrayList::new));

                    //Add the order to the last node on the path
                    routeToOrder.get(routeToOrder.size() - 1).orders.add(order);

                    //Calculate the path from the order location to the node after the attachment point
                    List<RouteNode> routeFromOrder = new ArrayList<>();

                    //If the attachment point is not the last node on the route, we don't need to add any more nodes
                    if (nodes.indexOf(possibleAttachment) != nodes.size() - 1) {
                        routeFromOrder = paths.get(nodes.get(nodes.indexOf(possibleAttachment) + 1)).stream()
                            .map(node -> new RouteNode(node, new ArrayList<>()))
                            .collect(Collectors.toCollection(ArrayList::new));
                        routeFromOrder.remove(routeFromOrder.size() - 1); //remove duplicate order delivery node
                        Collections.reverse(routeFromOrder);
                    }

                    routeToOrder.addAll(routeFromOrder);

                    //insert the path to and from the attachment directly after the attachment point
                    newRoute.addAll(nodes.indexOf(possibleAttachment) + 1, routeToOrder);

                    // If the new route is better than the current best route, switch to it
                    switch (compareRoute(bestNewRoute, newRoute, order, currentTick)) {
                        // If the order would be delivered to early we will not add it to a planned route for now
                        case BREAK -> {
                            pendingOrders.add(order);
                            return;
                        }
                        // If the new route is better, switch to it
                        case SWITCH -> {
                            bestVehicle = responsibleVehicle;
                            bestNewRoute = newRoute;
                        }
                    }
                }

            }

            // If no vehicle was found to be suitable for the order, add it to the pending orders
            if (bestVehicle == null) {
                pendingOrders.add(order);
                return;
            }

            // Only add the order to the planned route if it would not be delivered to early
            long deliveryDuration = getDeliveryDuration(order, bestNewRoute);
            if (deliveryDuration + currentTick > order.getDeliveryInterval().start()) {
                planedRoutes.put(bestVehicle, bestNewRoute);
                return;
            }

            // If the order would be delivered to early, add it to the pending orders
            pendingOrders.add(order);
        }

        /**
         * Executes a single tick for this {@link RestaurantManager} and send out vehicles if found to be necessary.
         *
         * @param currentTick The current tick.
         * @param newOrders   The new orders that arrived at the restaurant.
         */
        public void tick(long currentTick, List<ConfirmedOrder> newOrders) {

            // Accept all pending orders to check if they can be delivered now
            for (ConfirmedOrder order : new ArrayList<>(pendingOrders)) {
                pendingOrders.remove(order);
                acceptOrder(order, currentTick);
            }

            // Accept all new orders
            for (ConfirmedOrder order : newOrders) {
                acceptOrder(order, currentTick);
            }

            // Send out vehicles if found to be necessary
            for (Map.Entry<Vehicle, List<RouteNode>> plannedRoute : new HashSet<>(planedRoutes.entrySet())) {
                Vehicle responsibleVehicle = plannedRoute.getKey();
                List<RouteNode> route = plannedRoute.getValue();

                if (route.isEmpty()) {
                    continue;
                }

                // Check if the vehicle should be sent out and do so if necessary
                if (getTicksUntilOff(route, currentTick) < 5 || getWeight(route) >= 0.95 * responsibleVehicle.getCapacity()) {
                    moveVehicle(responsibleVehicle, currentTick);
                }
            }
        }

        /**
         * Adds a vehicle to this {@link RestaurantManager}.
         *
         * @param vehicle The vehicle to add.
         */
        public void addVehicle(Vehicle vehicle) {
            planedRoutes.put(vehicle, new ArrayList<>());
            queuedVehicles.remove(vehicle);
        }

        /**
         * Removes a vehicle from this {@link RestaurantManager}.
         *
         * @param vehicle The vehicle to remove.
         */
        public void removeVehicle(Vehicle vehicle) {
            planedRoutes.remove(vehicle);
        }

        /**
         * Adds a vehicle that will arrive at the manged {@link Region.Restaurant} after delivering all its orders.
         *
         * @param vehicle The vehicle to add.
         */
        public void addQueuedVehicle(Vehicle vehicle) {
            queuedVehicles.add(vehicle);
        }

        /**
         * Returns the {@link Region.Restaurant} that is managed by this {@link RestaurantManager}.
         *
         * @return The managed {@link Region.Restaurant}.
         */
        public Region.Restaurant getManaged() {
            return managed;
        }

        /**
         * All pending orders of the managed {@link Region.Restaurant}.
         *
         * @return all pending orders.
         */
        public Collection<? extends ConfirmedOrder> getPendingOrders() {
            return pendingOrders;
        }

        /**
         * Lets the given vehicle move along its planned route and deliver all orders.
         *
         * @param vehicle     The vehicle to move.
         * @param currentTick The current tick.
         */
        private void moveVehicle(Vehicle vehicle, long currentTick) {
            List<RouteNode> route = planedRoutes.get(vehicle);

            // Iterate over all nodes of the route
            for (RouteNode routeNode : route) {

                // If no orders are to be delivered at this node, continue
                if (routeNode.orders.isEmpty()) {
                    continue;
                }

                // Load all orders that are supposed to be delivered at this node
                for (ConfirmedOrder order : routeNode.orders) {
                    vehicleManager.getOccupiedRestaurant(managed).loadOrder(vehicle, order, currentTick);
                }

                // Move the vehicle to the node and deliver all orders upon arrival
                vehicle.moveQueued(routeNode.node(), (v, t) -> {
                    routeNode.orders().forEach(o -> {
                        vehicleManager.getOccupiedNeighborhood((Region.Node) v.getOccupied().getComponent()).deliverOrder(v, o, t);
                    });
                });
            }

            RestaurantManager leastVehiclesManager = null;

            // Find the manager with the least vehicles
            for (RestaurantManager restaurantManager : managers) {
                if (leastVehiclesManager == null
                    || restaurantManager.getTotalAvailableVehicle().size() < leastVehiclesManager.getTotalAvailableVehicle().size()) {
                    leastVehiclesManager = restaurantManager;
                }
            }

            assert leastVehiclesManager != null;

            // After delivering all orders, move the vehicle to the manager with the least vehicles
            vehicle.moveQueued(leastVehiclesManager.managed);
            leastVehiclesManager.addQueuedVehicle(vehicle);
            removeVehicle(vehicle);
        }

        private final static int KEEP = 0;
        private final static int SWITCH = 1;
        private final static int BREAK = 2;


        /**
         * Compares two routes and returns a value indicating which one is better for delivering the given {@link ConfirmedOrder}.
         *
         * @param oldRoute    The old route.
         * @param newRoute    The new route.
         * @param order       The order that is being delivered.
         * @param currentTick The current tick.
         * @return A value indicating which route is better. {@link #KEEP} if the new route is worse, {@link #SWITCH} if the new route is better.
         * and {@link #BREAK} if the new route is better but the order would be delivered too early.
         */
        private int compareRoute(List<RouteNode> oldRoute, List<RouteNode> newRoute, ConfirmedOrder order, long currentTick) {

            //if the order would be delivered too early, don't load it
            if (getDeliveryDuration(order, newRoute) + currentTick < order.getDeliveryInterval().start()) {
                return BREAK;
            }

            //if no old route is given choose the new route
            if (oldRoute == null) {
                return SWITCH;
            }

            long oldTicksOff = getTotalTicksOffForRoute(oldRoute, currentTick);
            long newTicksOff = getTotalTicksOffForRoute(newRoute, currentTick);

            //if both routes are on time, choose the one with the least distance
            if (oldTicksOff == 0 && newTicksOff == 0) {
                long oldDistance = getDistance(oldRoute);
                long newDistance = getDistance(newRoute);

                if (newDistance < oldDistance) {
                    return SWITCH;
                }

                return KEEP;
            }

            //if the new route is faster than the old route choose it
            if (newTicksOff < oldTicksOff) {
                return SWITCH;
            }

            //if the old route is faster than the new route keep it
            return KEEP;
        }


        /**
         * Returns the sum of the weights of all orders in the given route.
         *
         * @param route The route.
         * @return The sum of the weights of all orders in the given route.
         */
        private double getWeight(List<RouteNode> route) {
            double weight = 0;

            for (RouteNode routeNode : route) {
                for (ConfirmedOrder order : routeNode.orders) {
                    weight += order.getWeight();
                }
            }

            return weight;
        }

        /**
         * Returns the total distance of the given route.
         *
         * @param route The route.
         * @return The total distance of the given route.
         */
        private long getDistance(List<RouteNode> route) {
            long distance = 0L;
            Region.Node previous = managed;

            for (RouteNode routeNode : route) {
                distance += Objects.requireNonNull(region.getEdge(previous, routeNode.node)).getDuration();
                previous = routeNode.node;
            }

            return distance;
        }

        /**
         * Returns the amount of ticks it takes to deliver the given order when following the given route.
         *
         * @param order The order that would be delivered.
         * @param route The route that would be taken
         * @return The amount of ticks it takes to deliver the given order when following the given route.
         */
        private long getDeliveryDuration(ConfirmedOrder order, List<RouteNode> route) {
            long distance = 0L;
            Region.Node previous = managed;

            for (RouteNode routeNode : route) {
                distance += Objects.requireNonNull(region.getEdge(previous, routeNode.node())).getDuration();
                previous = routeNode.node;

                if (routeNode.node().getLocation().equals(order.getLocation())) {
                    return distance;
                }
            }

            throw new IllegalArgumentException("Order not in route");
        }

        /**
         * Returns the sum of the amount of ticks the orders of the route would be off when following the given route.
         *
         * @param route The route that would be taken.
         * @return the sum of the amount of ticks the orders of the route would be off when following the given route.
         */
        private long getTotalTicksOffForRoute(List<RouteNode> route, long currentTick) {

            Region.Node previous = managed;
            long distance = 0L;
            long ticksOff = 0L;

            for (RouteNode routeNode : route) {
                distance += Objects.requireNonNull(region.getEdge(previous, routeNode.node)).getDuration();
                previous = routeNode.node;

                for (ConfirmedOrder order : routeNode.orders) {
                    ticksOff += Math.abs(getTicksOff(order, distance + currentTick));
                }
            }

            return ticksOff;
        }

        /**
         * Returns the amount of ticks that can be waited until at least one order of the route would be delivered too late.
         *
         * @param route The route that would be taken.
         * @return the amount of ticks that can be waited until at least one order of the route would be delivered too late
         */
        private long getTicksUntilOff(List<RouteNode> route, long currentTick) {

            Region.Node previous = managed;
            long distance = 0L;
            long ticksUntilOff = Long.MAX_VALUE;

            for (RouteNode routeNode : route) {
                distance += Objects.requireNonNull(region.getEdge(previous, routeNode.node)).getDuration();
                previous = routeNode.node;

                for (ConfirmedOrder order : routeNode.orders) {
                    if (order.getDeliveryInterval().end() > distance + currentTick) {
                        ticksUntilOff = 0;
                    } else if (order.getDeliveryInterval().start() < distance + currentTick) {
                        ticksUntilOff = Math.min(ticksUntilOff, order.getDeliveryInterval().end() - currentTick - distance);
                    }
                }
            }

            return ticksUntilOff;
        }

        /**
         * Returns the amount of ticks the order would be off when delivered at the given time.
         *
         * @param order        The order.
         * @param deliveryTime The time the order would be delivered.
         * @return The amount of ticks the order would be off when delivered at the given time.
         */
        private long getTicksOff(ConfirmedOrder order, long deliveryTime) {
            TickInterval deliveryInterval = order.getDeliveryInterval();

            if (deliveryInterval.start() > deliveryTime) {
                return deliveryInterval.start() - deliveryTime;
            }

            if (deliveryTime > deliveryInterval.end()) {
                return deliveryTime - deliveryInterval.end();
            }

            return 0;

        }

        /**
         * Returns all vehicles that are not currently assigned to a route.
         *
         * @return All vehicles that are not currently assigned to a route.
         */
        public List<Vehicle> getUnusedVehicles() {
            return planedRoutes.keySet().stream().filter(v -> planedRoutes.get(v).isEmpty()).collect(Collectors.toList());
        }

        /**
         * Returns all vehicles that are currently or are expected to be available to the manager.
         *
         * @return All vehicles that are currently or are expected to be available to the manager.
         */
        public List<Vehicle> getTotalAvailableVehicle() {
            return queuedVehicles;
        }

    }

    /**
     * A record representing a node in a planned route of a {@link Vehicle}.
     *
     * @param node   The visited {@link Region.Node}.
     * @param orders The orders that are delivered at this {@link RouteNode}.
     */
    private record RouteNode(Region.Node node, List<ConfirmedOrder> orders) {

        /**
         * Returns a copy of this {@link RouteNode}.
         *
         * @return A copy of this {@link RouteNode}.
         */
        public RouteNode copy() {
            return new RouteNode(node, new ArrayList<>(orders));
        }

    }

    /**
     * Returns a copy of the given route.
     *
     * @param route The route to copy.
     * @return A copy of the given route.
     */
    private List<RouteNode> copyRoute(List<RouteNode> route) {
        return route.stream().map(RouteNode::copy).collect(Collectors.toCollection(ArrayList::new));
    }

}
