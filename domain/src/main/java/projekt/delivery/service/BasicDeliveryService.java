package projekt.delivery.service;

import projekt.delivery.event.Event;
import projekt.delivery.routing.ConfirmedOrder;
import projekt.delivery.routing.Region;
import projekt.delivery.routing.VehicleManager;

import java.util.*;

import static org.tudalgo.algoutils.student.Student.crash;

/**
 * A very simple delivery service that distributes orders to compatible vehicles in a FIFO manner.
 */
public class BasicDeliveryService extends AbstractDeliveryService {

    // List of orders that have not yet been loaded onto delivery vehicles
    protected final List<ConfirmedOrder> pendingOrders = new ArrayList<>();

    public BasicDeliveryService(
        VehicleManager vehicleManager
    ) {
        super(vehicleManager);
    }

    @Override
    protected List<Event> tick(long currentTick, List<ConfirmedOrder> newOrders) {
        // Move vehicles forward.
        List<Event> events = vehicleManager.tick(currentTick);

        // Add all newly arrived orders to the list of pending orders.
        pendingOrders.addAll(newOrders);

        // Prioritize orders according to their expected delivery times.
        pendingOrders.sort(Comparator.comparing(order -> order.getDeliveryInterval().start()));

        // For each vehicle waiting in the pizzeria, load as many orders as possible on the vehicle and send it out.
        for (VehicleManager.OccupiedRestaurant restaurant : vehicleManager.getOccupiedRestaurants()) {
            restaurant.getVehicles().stream()
                .filter(vehicle -> vehicle.getOrders().isEmpty()).forEach(vehicle -> {
                    boolean loadedAtLeastOneOrderOnVehicle = false;
                    ListIterator<ConfirmedOrder> it = pendingOrders.listIterator();
                    while (it.hasNext()) {
                        final ConfirmedOrder order = it.next();

                        //if the order does not belong to the current restaurant don't load it onto the vehicle
                        if (!order.getRestaurant().equals(restaurant)) {
                            continue;
                        }

                        //if the vehicle can load the order, load it and add the location to the moveQueue of the vehicle
                        if (order.getWeight() <= vehicle.getCapacity() - vehicle.getCurrentWeight()) {
                            loadedAtLeastOneOrderOnVehicle = true;
                            restaurant.loadOrder(vehicle, order, currentTick);
                            it.remove();

                            //don't add the location of the order to the queue if the vehicle already visits the location
                            if (vehicle.getPaths().stream()
                                .map(path -> path.nodes().peekLast())
                                .filter(Objects::nonNull)
                                .map(Region.Node::getLocation)
                                .toList().contains(order.getLocation())) {
                                continue;
                            }

                            vehicle.moveQueued(vehicleManager.getRegion().getNode(order.getLocation()), (v, t) -> {
                                //deliver every possible order
                                for (ConfirmedOrder deliveredOrder : v.getOrders().stream().filter(o -> o.getLocation().equals(order.getLocation())).toList()) {
                                    vehicleManager.getOccupiedNeighborhood((Region.Node) v.getOccupied().getComponent()).deliverOrder(v, deliveredOrder, t);
                                }
                            });
                        }
                    }

                    // If the vehicle leaves the pizzeria, ensure that it returns after delivering the last order.
                    if (loadedAtLeastOneOrderOnVehicle) {
                        vehicle.moveQueued(restaurant.getComponent());
                    }
                });
        }

        return events;

    }

    @Override
    public List<ConfirmedOrder> getPendingOrders() {
        return pendingOrders;
    }

    @Override
    public void reset() {
        super.reset();
        pendingOrders.clear();
    }

    public interface Factory extends DeliveryService.Factory {

        BasicDeliveryService create(VehicleManager vehicleManager);
    }
}
