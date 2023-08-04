package projekt.delivery.rating;

import projekt.delivery.event.DeliverOrderEvent;
import projekt.delivery.event.Event;
import projekt.delivery.event.OrderReceivedEvent;
import projekt.delivery.routing.ConfirmedOrder;
import projekt.delivery.simulation.Simulation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.tudalgo.algoutils.student.Student.crash;

/**
 * Rates the observed {@link Simulation} based on the amount of delivered orders.<p>
 *
 * To create a new {@link AmountDeliveredRater} use {@code AmountDeliveredRater.Factory.builder()...build();}.
 */
public class AmountDeliveredRater implements Rater {

    public static final RatingCriteria RATING_CRITERIA = RatingCriteria.AMOUNT_DELIVERED;

    private final double factor;
    private long ordersCount = 0;
    private final Set<ConfirmedOrder> pendingOrders = new HashSet<>();

    private AmountDeliveredRater(double factor) {
        this.factor = factor;
    }

    @Override
    public double getScore() {
        long undeliveredOrders = pendingOrders.size();
        double maxUndeliveredOrders = ordersCount * (1 - factor);

        if (undeliveredOrders > maxUndeliveredOrders || maxUndeliveredOrders == 0) {
            return 0;
        }

        return 1 - (undeliveredOrders / maxUndeliveredOrders);
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
            .forEach(deliverOrderEvent -> {
                ConfirmedOrder order = deliverOrderEvent.getOrder();

                if (!pendingOrders.remove(order)) {
                    throw new AssertionError("DeliverOrderEvent before OrderReceivedEvent");
                }
            });

        events.stream()
            .filter(OrderReceivedEvent.class::isInstance)
            .map(OrderReceivedEvent.class::cast)
            .map(OrderReceivedEvent::getOrder)
            .forEach(order -> {
                pendingOrders.add(order);
                ordersCount++;
            });
    }

    /**
     * A {@link Rater.Factory} for creating a new {@link AmountDeliveredRater}.
     */
    public static class Factory implements Rater.Factory {

        public final double factor;

        private Factory(double factor) {
            this.factor = factor;
        }

        @Override
        public AmountDeliveredRater create() {
            return new AmountDeliveredRater(factor);
        }

        /**
         * Creates a new {@link AmountDeliveredRater.FactoryBuilder}.
         * @return The created {@link AmountDeliveredRater.FactoryBuilder}.
         */
        public static FactoryBuilder builder() {
            return new FactoryBuilder();
        }
    }

    /**
     * A {@link Rater.FactoryBuilder} form constructing a new {@link AmountDeliveredRater.Factory}.
     */
    public static class FactoryBuilder implements Rater.FactoryBuilder {

        public double factor = 0.99;

        private FactoryBuilder() {}

        @Override
        public Factory build() {
            return new Factory(factor);
        }

        public FactoryBuilder setFactor(double factor) {
            if (factor < 0 || factor > 1) {
                throw new IllegalArgumentException("factor must be between 0 and 1");
            }

            this.factor = factor;
            return this;
        }
    }
}
