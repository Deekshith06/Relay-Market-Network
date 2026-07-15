package com.relaydelivery.service;

import com.relaydelivery.dao.OrderDao;
import com.relaydelivery.dao.SchedulingDao;
import com.relaydelivery.dao.SchedulingDao.SlotDefinition;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.util.ApiException;

import java.sql.SQLException;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

public final class GiftDeliveryService {
    public record SchedulePlan(long slotId,Instant scheduledAt,Instant assignmentReleaseAt,
                               Instant windowStart,Instant windowEnd,String timezone){}
    private static final Set<String> OCCASIONS=Set.of("BIRTHDAY","ANNIVERSARY","FESTIVAL","WEDDING",
        "GRADUATION","CONGRATULATIONS","THANK_YOU","SURPRISE","CUSTOM");
    private static final Pattern PHONE=Pattern.compile("^\\+?[0-9]{8,15}$");
    private final SchedulingDao schedules;private final OrderDao orders;private final Clock clock;
    public GiftDeliveryService(SchedulingDao schedules,OrderDao orders){this(schedules,orders,Clock.systemUTC());}
    GiftDeliveryService(SchedulingDao schedules,OrderDao orders,Clock clock){this.schedules=schedules;this.orders=orders;this.clock=clock;}

    public SchedulePlan validate(QuoteRequest request,List<PricedItem> items)throws SQLException{
        boolean gift=request.giftOptions()!=null&&Boolean.TRUE.equals(request.giftOptions().enabled());
        if(gift)validateGift(request.giftOptions());
        if(request.scheduledAt()==null||request.scheduledAt().isBlank()){
            if(gift)throw ApiException.badRequest("Gift delivery requires a future date and time");
            return null;
        }
        ZoneId zone=parseZone(request.timezone());Instant scheduled=parseInstant(request.scheduledAt(),"Scheduled date and time");
        Instant now=clock.instant();
        if(!scheduled.isAfter(now))throw ApiException.badRequest("Scheduled delivery must be in the future");
        if(scheduled.isAfter(now.plus(Duration.ofDays(90))))throw ApiException.badRequest("Scheduled delivery is more than 90 days away");
        ZonedDateTime local=scheduled.atZone(zone);LocalDate date=local.toLocalDate();
        SlotDefinition slot=matchingSlot(date,local.toLocalTime());
        if(scheduled.isBefore(now.plus(Duration.ofMinutes(slot.preparationMinutes()))))
            throw ApiException.conflict("The selected slot does not allow enough preparation time");
        Instant start=date.atTime(slot.start()).atZone(zone).toInstant();Instant end=date.atTime(slot.end()).atZone(zone).toInstant();
        if(orders.scheduledUsage(start,end)>=slot.capacity())throw ApiException.conflict("The selected delivery slot is full");
        long daysUntil=Math.max(1,Duration.between(now,scheduled).toDays()+1);
        if(items.stream().anyMatch(i->i.shelfLifeDays()!=null&&i.shelfLifeDays()<daysUntil))
            throw ApiException.conflict("A perishable cart item will not remain valid until the selected delivery date");
        return new SchedulePlan(slot.id(),scheduled,scheduled.minus(Duration.ofMinutes(60)),start,end,zone.getId());
    }

    // The slot row is the transaction mutex: concurrent checkouts cannot both observe the final capacity.
    public void lockCapacity(Connection c,SchedulePlan plan)throws SQLException{
        SlotDefinition slot;
        try{slot=schedules.lock(c,plan.slotId());}catch(SQLException e){
            if("P0001".equals(e.getSQLState()))throw ApiException.conflict("The selected delivery slot is no longer available");throw e;
        }
        if(orders.scheduledUsage(c,plan.windowStart(),plan.windowEnd())>=slot.capacity())
            throw ApiException.conflict("The selected delivery slot is full");
    }

    public List<TimeSlot> slots(LocalDate date,String timezone)throws SQLException{
        ZoneId zone=parseZone(timezone);Instant now=clock.instant();List<TimeSlot> values=new ArrayList<>();
        for(SlotDefinition slot:schedules.definitions(postgresDay(date.getDayOfWeek()))){
            Instant start=date.atTime(slot.start()).atZone(zone).toInstant();Instant end=date.atTime(slot.end()).atZone(zone).toInstant();
            if(end.isBefore(now))continue;int remaining=Math.max(0,slot.capacity()-orders.scheduledUsage(start,end));
            values.add(new TimeSlot(slot.id(),slot.name(),date.toString(),slot.start().toString(),slot.end().toString(),remaining));
        }return values;
    }

    private SlotDefinition matchingSlot(LocalDate date,LocalTime time)throws SQLException{
        return schedules.definitions(postgresDay(date.getDayOfWeek())).stream()
            .filter(s->!time.isBefore(s.start())&&time.isBefore(s.end())).findFirst()
            .orElseThrow(()->ApiException.badRequest("Scheduled time is outside an available delivery slot"));
    }
    private static void validateGift(GiftOptions g){
        clean(g.recipientName(),"Recipient name",2,100);if(g.recipientPhone()==null||!PHONE.matcher(g.recipientPhone().trim()).matches())
            throw ApiException.badRequest("Enter a valid recipient phone number");
        String occasion=g.occasion()==null?"":g.occasion().toUpperCase(Locale.ROOT);if(!OCCASIONS.contains(occasion))throw ApiException.badRequest("Select a valid gift occasion");
        optional(g.giftMessage(),"Gift message",500);optional(g.senderName(),"Sender name",100);optional(g.deliveryInstructions(),"Delivery instructions",500);
        if(!Set.of("ECO","STANDARD","PREMIUM").contains(g.wrappingStyle()==null?"":g.wrappingStyle().toUpperCase(Locale.ROOT)))
            throw ApiException.badRequest("Select a valid gift wrapping style");
    }
    private static ZoneId parseZone(String value){try{return ZoneId.of(value==null||value.isBlank()?"Asia/Kolkata":value);}catch(DateTimeException e){throw ApiException.badRequest("Timezone is invalid");}}
    private static Instant parseInstant(String value,String label){try{return Instant.parse(value);}catch(DateTimeException e){try{return OffsetDateTime.parse(value).toInstant();}catch(DateTimeException ignored){throw ApiException.badRequest(label+" is invalid");}}}
    private static int postgresDay(DayOfWeek day){return day==DayOfWeek.SUNDAY?0:day.getValue();}
    private static void clean(String value,String label,int min,int max){int length=value==null?0:value.trim().length();if(length<min||length>max)throw ApiException.badRequest(label+" must be "+min+"-"+max+" characters");}
    private static void optional(String value,String label,int max){if(value!=null&&value.length()>max)throw ApiException.badRequest(label+" is too long");}
}
