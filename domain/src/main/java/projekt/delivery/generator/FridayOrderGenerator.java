package projekt.delivery.generator;

import projekt.base.Location;
import projekt.base.TickInterval;
import projekt.delivery.routing.ConfirmedOrder;
import projekt.delivery.routing.VehicleManager;

import java.util.*;

import static org.tudalgo.algoutils.student.Student.crash;

/**
 * An implementation of an {@link OrderGenerator} that represents the incoming orders on an average friday evening.
 * The incoming orders follow a normal distribution.<p>
 *
 * To create a new {@link FridayOrderGenerator} use {@code FridayOrderGenerator.Factory.builder()...build();}.
 */
public class FridayOrderGenerator implements OrderGenerator {

    private final Random random;
    private final Map<Long, List<ConfirmedOrder>> orders = new HashMap<>();

    /**
     * Creates a new {@link FridayOrderGenerator} with the given parameters.
     * @param orderCount The total amount of orders this {@link OrderGenerator} will create. It is equal to the sum of
     *                   the size of the lists that are returned for every positive long value.
     * @param vehicleManager The {@link VehicleManager} this {@link OrderGenerator} will create orders for.
     * @param deliveryInterval The amount of ticks between the start and end tick of the deliveryInterval of the created orders.
     * @param maxWeight The maximum weight of a created order.
     * @param standardDeviation The standardDeviation of the normal distribution.
     * @param lastTick The last tick this {@link OrderGenerator} can return a non-empty list.
     * @param seed The seed for the used {@link Random} instance. If negative a random seed will be used.
     */
    private FridayOrderGenerator(int orderCount, VehicleManager vehicleManager, int deliveryInterval, double maxWeight, double standardDeviation, long lastTick, int seed) {
        random = seed < 0 ? new Random() : new Random(seed);

        for (int i = 0; i < orderCount; i++) {
            long deliveryTime;
            do {
                deliveryTime = (long) ((random.nextGaussian(0.5, standardDeviation)) * lastTick);
            } while (deliveryTime < 0.0 || deliveryTime > lastTick);

            if (orders.containsKey(deliveryTime)) {
                orders.get(deliveryTime).add(createRandomOrder(vehicleManager, deliveryTime, deliveryInterval, maxWeight));
            } else {
                orders.put(deliveryTime, new ArrayList<>(List.of(createRandomOrder(vehicleManager, deliveryTime, deliveryInterval, maxWeight))));
            }
        }
    }

    @Override
    public List<ConfirmedOrder> generateOrders(long tick) {
        if (tick < 0) {
            throw new IndexOutOfBoundsException(tick);
        }

        return orders.getOrDefault(tick, List.of());
    }

    private ConfirmedOrder createRandomOrder(VehicleManager vehicleManager, long deliveryTime, long deliveryInterval, double maxWeight) {
        VehicleManager.OccupiedRestaurant restaurant = new ArrayList<>(vehicleManager.getOccupiedRestaurants())
            .get(random.nextInt(vehicleManager.getOccupiedRestaurants().size()));
        Location location = new ArrayList<>(vehicleManager.getOccupiedNeighborhoods())
            .get(random.nextInt(vehicleManager.getOccupiedNeighborhoods().size())).getComponent().getLocation();
        double actualMaxWeight = random.nextDouble(maxWeight);
        int foodCount = random.nextInt(1, 10);
        List<String> foodList = new ArrayList<>();

        for (int i = 0; i < foodCount; i++) {
            foodList.add(restaurant.getComponent().getAvailableFood().get(random.nextInt(restaurant.getComponent().getAvailableFood().size())));
        }

        return new ConfirmedOrder(
            location,
            restaurant,
            new TickInterval(deliveryTime , deliveryTime + deliveryInterval),
            foodList,
            actualMaxWeight);
    }

    /**
     * A {@link OrderGenerator.Factory} for creating a new {@link FridayOrderGenerator}.
     */
    public static class Factory implements OrderGenerator.Factory {

        public final int orderCount;
        public final VehicleManager vehicleManager;
        public final int deliveryInterval;
        public final double maxWeight;
        public final double standardDeviation;
        public final long lastTick;
        public final int seed;

        private Factory(int orderCount, VehicleManager vehicleManager, int deliveryInterval, double maxWeight, double standardDeviation, long lastTick, int seed) {
            this.orderCount = orderCount;
            this.vehicleManager = vehicleManager;
            this.deliveryInterval = deliveryInterval;
            this.maxWeight = maxWeight;
            this.standardDeviation = standardDeviation;
            this.lastTick = lastTick;
            this.seed = seed;
        }

        @Override
        public OrderGenerator create() {
            return new FridayOrderGenerator(orderCount, vehicleManager, deliveryInterval, maxWeight, standardDeviation, lastTick, seed);
        }

        /**
         * Creates a new {@link FridayOrderGenerator.FactoryBuilder}.
         * @return The created {@link FridayOrderGenerator.FactoryBuilder}.
         */
        public static FridayOrderGenerator.FactoryBuilder builder() {
            return new FridayOrderGenerator.FactoryBuilder();
        }
    }


    /**
     * A {@link OrderGenerator.FactoryBuilder} form constructing a new {@link FridayOrderGenerator.Factory}.
     */
    public static class FactoryBuilder implements OrderGenerator.FactoryBuilder {

        public int orderCount = 1000;
        public VehicleManager vehicleManager = null;
        public int deliveryInterval = 15;
        public double maxWeight = 0.5;
        public double standardDeviation = 0.5;
        public long lastTick = 480;
        public int seed = -1;

        private FactoryBuilder() {}

        public FactoryBuilder setOrderCount(int orderCount) {
            this.orderCount = orderCount;
            return this;
        }

        public FactoryBuilder setVehicleManager(VehicleManager vehicleManager) {
            this.vehicleManager = vehicleManager;
            return this;
        }

        public FactoryBuilder setDeliveryInterval(int deliveryInterval) {
            this.deliveryInterval = deliveryInterval;
            return this;
        }

        public FactoryBuilder setMaxWeight(double maxWeight) {
            this.maxWeight = maxWeight;
            return this;
        }

        public FactoryBuilder setStandardDeviation(double standardDeviation) {
            this.standardDeviation = standardDeviation;
            return this;
        }

        public FactoryBuilder setLastTick(long lastTick) {
            this.lastTick = lastTick;
            return this;
        }

        public FactoryBuilder setSeed(int seed) {
            this.seed = seed;
            return this;
        }

        @Override
        public Factory build() {
            Objects.requireNonNull(vehicleManager);
            return new Factory(orderCount, vehicleManager, deliveryInterval, maxWeight, standardDeviation, lastTick, seed);
        }
    }
}
