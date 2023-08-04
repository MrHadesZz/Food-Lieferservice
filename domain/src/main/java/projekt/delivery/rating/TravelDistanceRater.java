package projekt.delivery.rating;

import projekt.delivery.event.ArrivedAtNodeEvent;
import projekt.delivery.event.DeliverOrderEvent;
import projekt.delivery.event.Event;
import projekt.delivery.routing.PathCalculator;
import projekt.delivery.routing.Region;
import projekt.delivery.routing.VehicleManager;
import projekt.delivery.simulation.Simulation;

import java.util.Deque;
import java.util.List;
import java.util.Objects;

import static org.tudalgo.algoutils.student.Student.crash;


/**
 * Rates the observed {@link Simulation} based on the distance traveled by all vehicles.<p>
 *
 * To create a new {@link TravelDistanceRater} use {@code TravelDistanceRater.Factory.builder()...build();}.
 */
public class TravelDistanceRater implements Rater {

    public static final RatingCriteria RATING_CRITERIA = RatingCriteria.TRAVEL_DISTANCE;

    private final Region region;
    private final PathCalculator pathCalculator;
    private final double factor;

    private long worstDistance = 0;
    private long actualDistance = 0;

    private TravelDistanceRater(VehicleManager vehicleManager, double factor) {
        region = vehicleManager.getRegion();
        pathCalculator = vehicleManager.getPathCalculator();
        this.factor = factor;
    }

    @Override
    public double getScore() {
        double actualWorstDistance = worstDistance * factor;

        if (actualDistance >= actualWorstDistance || actualWorstDistance == 0) {
            return 0;
        }

        return 1 - (actualDistance / actualWorstDistance);
    }

    @Override
    public RatingCriteria getRatingCriteria() {
        return RATING_CRITERIA;
    }

    @Override
    public void onTick(List<Event> events, long tick) {
        events.stream()
            .filter(DeliverOrderEvent.class::isInstance)
            .map(DeliverOrderEvent.class::cast)
            .forEach(deliverOrderEvent -> worstDistance += 2 * getDistance(deliverOrderEvent.getOrder().getRestaurant().getComponent(),
                region.getNode(deliverOrderEvent.getOrder().getLocation())));

        events.stream()
            .filter(ArrivedAtNodeEvent.class::isInstance)
            .map(ArrivedAtNodeEvent.class::cast)
            .forEach(arrivedAtNodeEvent -> actualDistance += arrivedAtNodeEvent.getLastEdge().getDuration());
    }
    private double getDistance(Region.Node node1, Region.Node node2) {
        Deque<Region.Node> path = pathCalculator.getPath(node1, node2);

        if (path.isEmpty()) {
            return 0;
        }

        long distance = 0;
        Region.Node previousNode = node1;
        Region.Node node = path.pollFirst();

        do {
            assert node != null;
            distance += Objects.requireNonNull(region.getEdge(previousNode, node)).getDuration();
            previousNode = node;
            node = path.pollFirst();
        } while (!path.isEmpty());

        return distance;
    }
    /**
     * A {@link Rater.Factory} for creating a new {@link TravelDistanceRater}.
     */
    public static class Factory implements Rater.Factory {

        public final VehicleManager vehicleManager;
        public final double factor;

        private Factory(VehicleManager vehicleManager, double factor) {
            this.vehicleManager = vehicleManager;
            this.factor = factor;
        }

        @Override
        public TravelDistanceRater create() {
            return new TravelDistanceRater(vehicleManager, factor);
        }

        /**
         * Creates a new {@link TravelDistanceRater.FactoryBuilder}.
         * @return The created {@link TravelDistanceRater.FactoryBuilder}.
         */
        public static FactoryBuilder builder() {
            return new FactoryBuilder();
        }


    }

    /**
     * A {@link Rater.FactoryBuilder} form constructing a new {@link TravelDistanceRater.Factory}.
     */
    public static class FactoryBuilder implements Rater.FactoryBuilder {

        public VehicleManager vehicleManager;
        public double factor = 0.5;

        private FactoryBuilder() {}

        @Override
        public Factory build() {
            return new Factory(vehicleManager, factor);
        }

        public FactoryBuilder setVehicleManager(VehicleManager vehicleManager) {
            this.vehicleManager = vehicleManager;
            return this;
        }

        public FactoryBuilder setFactor(double factor) {
            if (factor < 0) {
                throw new IllegalArgumentException("factor must be positive");
            }

            this.factor = factor;
            return this;
        }
    }

}
