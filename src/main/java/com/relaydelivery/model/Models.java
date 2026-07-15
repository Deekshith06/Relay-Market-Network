package com.relaydelivery.model;

import java.math.BigDecimal;
import java.util.List;

public final class Models {
    private Models() {}

    public enum Role { CUSTOMER, AGENT, ADMIN }
    public enum AgentStatus { AVAILABLE, BUSY, OFFLINE }
    public enum Priority { STANDARD, EXPRESS, CRITICAL }
    public enum OrderType { NORMAL, GIFT }
    public enum PricingType { FIXED, WEIGHT, VARIANT }
    public enum OrderStatus { SCHEDULED, PLACED, CONFIRMED, PACKED, ASSIGNED, PICKED_UP,
        OUT_FOR_DELIVERY, DELIVERY_VERIFICATION, DELIVERED, CANCELLED }
    public enum RuleType { BASE_DELIVERY, DISTANCE, WEIGHT, CATEGORY_HANDLING, CATEGORY_PACKAGING,
        FRAGILE, TEMPERATURE_CONTROL, INSURANCE, PRIORITY, GIFT_WRAP, SCHEDULED_DELIVERY,
        PLATFORM_FEE, TAX }

    public record User(long id, String name, String email, String phone,
                       String passwordHash, Role role) {}

    public record Agent(long id, long userId, String name, String phone,
                        double latitude, double longitude, AgentStatus status,
                        long vehicleTypeId, String vehicleType, BigDecimal maximumCapacityKg,
                        BigDecimal currentLoadKg, int maximumActiveOrders, int currentActiveOrders,
                        boolean supportsFragile, boolean supportsTemperatureControl,
                        String handlingEquipment, BigDecimal rating) {}

    public record Category(long id, String name, String description, String handlingType,
                           boolean fragileDefault, boolean temperatureControlledDefault,
                           boolean requiresInsuranceDefault, int maxQuantity,
                           BigDecimal maxDeliveryDistanceKm, String deliveryRestrictions,
                           boolean returnEligible, Priority defaultPriority, boolean active) {}

    public record ProductVariant(long id, long productId, String label, String sku,
                                 BigDecimal unitPrice, BigDecimal pricePerKg,
                                 BigDecimal unitWeightKg, BigDecimal minimumOrderWeight,
                                 BigDecimal maximumOrderWeight, BigDecimal stockQuantity,
                                 String deliveryRestriction, boolean active) {}

    public record Product(long id, long categoryId, String categoryName, String name,
                          String description, String sku, PricingType pricingType,
                          BigDecimal unitPrice, BigDecimal pricePerKg, BigDecimal unitWeightKg,
                          BigDecimal minimumOrderWeight, BigDecimal maximumOrderWeight,
                          BigDecimal packagingWeightKg, BigDecimal stockQuantity,
                          boolean fragile, boolean temperatureControlled, boolean requiresInsurance,
                          Integer shelfLifeDays, String imageUrl, boolean active,
                          List<ProductVariant> variants) {}

    public record CartItemRequest(long productId, Long variantId, Integer quantity,
                                  BigDecimal selectedWeightKg) {}

    public record CartItem(long id, long productId, Long variantId, int quantity,
                           BigDecimal selectedWeightKg, Product product) {}

    public record Cart(long id, List<CartItem> items) {}

    public record Address(long id, long customerId, String label, String addressLine,
                          String landmark, String instructions, double latitude,
                          double longitude, long zoneId, boolean isDefault,
                          String createdAt) {}

    public record AddressInput(String label, String addressLine, String landmark,
                               String instructions, double latitude, double longitude,
                               long zoneId, Boolean isDefault) {}

    public record GeoPoint(String address, double latitude, double longitude,
                           long zoneId, String landmark, String instructions) {}

    public record GiftOptions(Boolean enabled, String recipientName, String recipientPhone,
                              String occasion, String giftMessage, String senderName,
                              Boolean hideSender, Boolean hidePrice, String wrappingStyle,
                              String cardStyle, Boolean surpriseDelivery,
                              Boolean recipientOtpRequired, String deliveryInstructions) {}

    public record QuoteRequest(List<CartItemRequest> cartItems, GeoPoint pickupLocation,
                               GeoPoint deliveryLocation, Priority priority,
                               GiftOptions giftOptions, String scheduledAt,
                               String deliveryWindowStart, String deliveryWindowEnd,
                               String timezone, String couponCode) {}

    public record PricedItem(long productId, Long variantId, long categoryId,
                             String categoryName, String productName, String variantLabel, int quantity,
                             BigDecimal selectedWeightKg, BigDecimal stockDemand,
                             BigDecimal unitPrice, BigDecimal pricePerKg,
                             BigDecimal unitWeightKg, BigDecimal lineSubtotal,
                             boolean fragile, boolean temperatureControlled,
                             boolean requiresInsurance, Integer shelfLifeDays,
                             BigDecimal maxDeliveryDistanceKm) {}

    public record ChargeLine(String type, String label, BigDecimal amount, int sortOrder) {}

    public record PricingRule(long id, RuleType type, Long categoryId, String appliesTo,
                              BigDecimal minimumValue, BigDecimal maximumValue,
                              BigDecimal flatAmount, BigDecimal perUnitAmount,
                              BigDecimal percentageAmount, int priority, boolean active) {}

    public record Quote(String quoteId, String currency, BigDecimal productSubtotal,
                        BigDecimal baseDeliveryFee, BigDecimal distanceKm,
                        BigDecimal distanceCharge, BigDecimal totalWeightKg,
                        BigDecimal weightCharge, List<ChargeLine> categoryCharges,
                        List<ChargeLine> specialHandlingCharges, BigDecimal giftCharge,
                        BigDecimal schedulingCharge, BigDecimal platformFee,
                        BigDecimal tax, BigDecimal discount, BigDecimal totalDeliveryCharge,
                        BigDecimal finalPayableAmount, List<ChargeLine> chargeLines,
                        String expiresAt) {}

    public record QuoteSnapshot(Quote quote, QuoteRequest request, List<PricedItem> items,
                                List<ChargeLine> lines, long customerId, String requestHash,
                                String usedAt) {}

    public record PlaceOrderRequest(String quoteId) {}

    public record Order(long id, long customerId, Long agentId, String customerName,
                        String agentName, String pickupAddress, String dropAddress,
                        double pickupLat, double pickupLng, double dropLat, double dropLng,
                        long pickupZoneId, long dropZoneId, String pickupZone, String dropZone,
                        String packageName, int itemCount, BigDecimal totalWeightKg,
                        String notes, OrderStatus status, OrderType orderType,
                        Priority priority, boolean fragile, boolean temperatureControlled,
                        BigDecimal price, String scheduledDeliveryAt,
                        String deliveryWindowStart, String deliveryWindowEnd,
                        String timezone, String createdAt, String deliveredAt,
                        boolean hidePrice, String occasion, int version) {}

    public record OrderItem(long id, long productId, Long variantId, String productName,
                            String variantLabel, int quantity, BigDecimal selectedWeightKg,
                            BigDecimal unitPrice, BigDecimal pricePerKg,
                            BigDecimal unitWeightKg, BigDecimal lineSubtotal) {}

    public record GiftDetails(String recipientName, String maskedRecipientPhone,
                              String occasion, String giftMessage, String senderDisplayName,
                              boolean hideSender, boolean hidePrice, String wrappingStyle,
                              String cardStyle, boolean surpriseDelivery,
                              boolean recipientOtpRequired, String scheduledAt,
                              String deliveryWindowStart, String deliveryWindowEnd,
                              String timezone, String deliveryInstructions) {}

    public record LocationUpdate(Long orderId, double latitude, double longitude,
                                 double accuracyMeters, Double heading,
                                 Double speedMetersPerSecond, String recordedAt) {}

    public record AgentLocation(long agentId, long orderId, double latitude,
                                double longitude, double accuracyMeters, Double heading,
                                Double speedMetersPerSecond, String recordedAt,
                                String receivedAt) {}

    public record TimeSlot(long id, String name, String date, String startTime,
                           String endTime, int remainingCapacity) {}

    public record ZoneEdge(long from, long to, double distance) {}
    public record Route(double distanceKm, List<Long> zoneIds) {}
}
