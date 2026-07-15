package com.relaydelivery.controller;

import com.relaydelivery.config.Database;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.service.*;
import com.relaydelivery.util.ApiException;
import com.relaydelivery.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

public final class ApiController implements HttpHandler {
    private record LoginBody(String email,String password){}
    private record RegisterBody(String name,String email,String password,String role,String vehicleType){}
    private record StatusBody(String status){}
    private record AvailabilityBody(String status,Double latitude,Double longitude){}
    private record ReassignBody(Long agentId,Integer expectedOrderVersion){}
    private record StockBody(Long variantId,BigDecimal stockQuantity){}
    private record ProductStatusBody(Boolean active){}
    private record VerifyDeliveryBody(String deliveryCode,String reason){}

    private static final Pattern PRODUCT=Pattern.compile("^/products/(\\d+)$");
    private static final Pattern CART_ITEM=Pattern.compile("^/cart/items/(\\d+)$");
    private static final Pattern ADDRESS=Pattern.compile("^/addresses/(\\d+)$");
    private static final Pattern ORDER=Pattern.compile("^/orders/(\\d+)$");
    private static final Pattern ASSIGN=Pattern.compile("^/orders/(\\d+)/assign$");
    private static final Pattern STATUS=Pattern.compile("^/orders/(\\d+)/status$");
    private static final Pattern TRACKING=Pattern.compile("^/orders/(\\d+)/tracking$");
    private static final Pattern VERIFY_DELIVERY=Pattern.compile("^/orders/(\\d+)/verify-delivery$");
    private static final Pattern REASSIGN=Pattern.compile("^/admin/orders/(\\d+)/reassign$");
    private static final Pattern ADMIN_ORDER=Pattern.compile("^/admin/orders/(\\d+)$");
    private static final Pattern ADMIN_ORDER_AGENTS=Pattern.compile("^/admin/orders/(\\d+)/available-agents$");
    private static final Pattern ADMIN_ORDER_ASSIGN=Pattern.compile("^/admin/orders/(\\d+)/assign$");
    private static final Pattern ADMIN_ORDER_STATUS=Pattern.compile("^/admin/orders/(\\d+)/status$");
    private static final Pattern ADMIN_ORDER_CANCEL=Pattern.compile("^/admin/orders/(\\d+)/cancel$");
    private static final Pattern ADMIN_VERIFY_DELIVERY=Pattern.compile("^/admin/orders/(\\d+)/verify-delivery$");
    private static final Pattern ADMIN_PRODUCT=Pattern.compile("^/admin/products/(\\d+)$");
    private static final Pattern ADMIN_STOCK=Pattern.compile("^/admin/products/(\\d+)/stock$");
    private static final Pattern ADMIN_PRODUCT_STATUS=Pattern.compile("^/admin/products/(\\d+)/status$");
    private static final Pattern ADMIN_VARIANT=Pattern.compile("^/admin/products/(\\d+)/variants$");
    private static final Pattern ADMIN_RULE=Pattern.compile("^/admin/pricing-rules/(\\d+)$");
    private static final String ALLOWED_ORIGIN=Database.env("CORS_ORIGIN","");

    private final AuthService auth;private final SessionService sessions;private final OrderService orders;
    private final AgentService agents;private final OperationsService operations;private final LoginRateLimiter loginLimiter;
    private final ProductCatalogService catalog;private final QuoteService quotes;private final AddressService addresses;
    private final GiftDeliveryService gifts;private final LocationTrackingService tracking;private final PricingAdminService pricing;
    public ApiController(AuthService auth,SessionService sessions,OrderService orders,AgentService agents,
                         OperationsService operations,LoginRateLimiter loginLimiter,ProductCatalogService catalog,
                         QuoteService quotes,AddressService addresses,GiftDeliveryService gifts,
                         LocationTrackingService tracking,PricingAdminService pricing){
        this.auth=auth;this.sessions=sessions;this.orders=orders;this.agents=agents;this.operations=operations;
        this.loginLimiter=loginLimiter;this.catalog=catalog;this.quotes=quotes;this.addresses=addresses;
        this.gifts=gifts;this.tracking=tracking;this.pricing=pricing;
    }

    @Override public void handle(HttpExchange x)throws IOException{
        addHeaders(x);
        if(x.getRequestMethod().equals("OPTIONS")){
            String origin=x.getRequestHeaders().getFirst("Origin");
            if(origin!=null&&!origin.equals(ALLOWED_ORIGIN))send(x,403,Map.of("error","Origin is not allowed"));else x.sendResponseHeaders(204,-1);
            x.close();return;
        }
        try{route(x);}catch(ApiException e){send(x,e.status(),Map.of("error",e.getMessage()));}
        catch(IllegalArgumentException e){send(x,400,Map.of("error","Request contains an invalid value"));}
        catch(SQLException e){System.err.println("Database error: "+e.getSQLState()+" "+e.getMessage());send(x,500,Map.of("error","A database operation failed"));}
        catch(Exception e){e.printStackTrace(System.err);send(x,500,Map.of("error","Unexpected server error"));}
        finally{x.close();}
    }

    private void route(HttpExchange x)throws Exception{
        String method=x.getRequestMethod(),path=x.getRequestURI().getPath().substring(4);
        if(method.equals("POST")&&path.equals("/auth/login")){LoginBody b=read(x,LoginBody.class);send(x,200,login(x,b));return;}
        if(method.equals("POST")&&path.equals("/auth/register")){RegisterBody b=read(x,RegisterBody.class);send(x,201,auth.register(b.name(),b.email(),b.password(),b.role(),b.vehicleType()));return;}
        User actor=requireUser(x);
        if(method.equals("POST")&&path.equals("/auth/logout")){sessions.revoke(token(x));send(x,200,Map.of("ok",true));return;}
        if(method.equals("GET")&&path.equals("/zones")){send(x,200,operations.zones());return;}
        if(method.equals("GET")&&path.equals("/categories")){send(x,200,catalog.categories(actor));return;}
        if(method.equals("GET")&&path.equals("/products")){Map<String,String> q=query(x);Long category=number(q.get("category"));send(x,200,catalog.products(actor,category,q.get("search")));return;}
        Matcher match=PRODUCT.matcher(path);
        if(method.equals("GET")&&match.matches()){send(x,200,catalog.product(actor,id(match)));return;}

        if(method.equals("GET")&&path.equals("/cart")){send(x,200,catalog.cart(actor));return;}
        if(method.equals("POST")&&path.equals("/cart/items")){send(x,201,catalog.add(actor,read(x,CartItemRequest.class)));return;}
        match=CART_ITEM.matcher(path);
        if(method.equals("PATCH")&&match.matches()){send(x,200,catalog.update(actor,id(match),read(x,CartItemRequest.class)));return;}
        if(method.equals("DELETE")&&match.matches()){send(x,200,catalog.delete(actor,id(match)));return;}
        if(method.equals("POST")&&path.equals("/pricing/quote")){send(x,201,quotes.create(actor,read(x,QuoteRequest.class)));return;}

        if(method.equals("GET")&&path.equals("/addresses")){send(x,200,addresses.list(actor));return;}
        if(method.equals("POST")&&path.equals("/addresses")){send(x,201,addresses.save(actor,null,read(x,AddressInput.class)));return;}
        match=ADDRESS.matcher(path);
        if(method.equals("PUT")&&match.matches()){send(x,200,addresses.save(actor,id(match),read(x,AddressInput.class)));return;}
        if(method.equals("DELETE")&&match.matches()){addresses.delete(actor,id(match));send(x,200,Map.of("ok",true));return;}
        if(method.equals("GET")&&path.equals("/delivery/slots")){Map<String,String> q=query(x);LocalDate date;
            try{date=LocalDate.parse(q.getOrDefault("date",""));}catch(Exception e){throw ApiException.badRequest("Date must use YYYY-MM-DD");}
            send(x,200,gifts.slots(date,q.getOrDefault("timezone","Asia/Kolkata")));return;}

        if(method.equals("GET")&&path.equals("/orders")){send(x,200,orders.list(actor));return;}
        if(method.equals("POST")&&path.equals("/orders")){sendOrder(x,201,actor,orders.create(actor,read(x,PlaceOrderRequest.class),x.getRequestHeaders().getFirst("Idempotency-Key")));return;}
        match=ORDER.matcher(path);
        if(method.equals("GET")&&match.matches()){sendOrder(x,200,actor,orders.get(actor,id(match)));return;}
        match=TRACKING.matcher(path);
        if(method.equals("GET")&&match.matches()){send(x,200,tracking.tracking(actor,id(match)));return;}
        match=VERIFY_DELIVERY.matcher(path);
        if(method.equals("POST")&&match.matches()){VerifyDeliveryBody b=read(x,VerifyDeliveryBody.class);
            sendVerifiedOrder(x,actor,orders.verifyDelivery(actor,id(match),b.deliveryCode(),null,remoteIp(x)));return;}
        match=ASSIGN.matcher(path);
        if(method.equals("POST")&&match.matches()){sendOrder(x,200,actor,orders.autoAssign(actor,id(match)));return;}
        match=STATUS.matcher(path);
        if(method.equals("POST")&&match.matches()){StatusBody b=read(x,StatusBody.class);OrderStatus status;
            try{status=OrderStatus.valueOf(b.status()==null?"":b.status().toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){throw ApiException.badRequest("Unknown order status");}
            sendOrder(x,200,actor,orders.updateStatus(actor,id(match),status));return;}

        if(method.equals("GET")&&path.equals("/agents/available")){send(x,200,agents.available(actor));return;}
        if(method.equals("GET")&&path.equals("/agents/me")){send(x,200,agents.me(actor));return;}
        if(method.equals("POST")&&path.equals("/agents/me/status")){AvailabilityBody b=read(x,AvailabilityBody.class);send(x,200,agents.setAvailability(actor,b.status(),b.latitude(),b.longitude()));return;}
        if(method.equals("POST")&&path.equals("/agents/me/location")){send(x,202,tracking.update(actor,read(x,LocationUpdate.class)));return;}
        if(method.equals("DELETE")&&path.equals("/agents/me/location")){send(x,200,tracking.stop(actor));return;}

        if(method.equals("GET")&&path.equals("/admin/overview")){Map<String,Object> result=new LinkedHashMap<>(operations.analytics(actor));
            result.put("orders",orders.list(actor));result.put("categories",catalog.categories(actor));result.put("products",catalog.products(actor,null,null));
            result.put("pricingRules",pricing.list(actor));send(x,200,result);return;}
        if(method.equals("GET")&&path.equals("/admin/orders")){send(x,200,orders.list(actor));return;}
        match=ADMIN_ORDER.matcher(path);
        if(method.equals("GET")&&match.matches()){sendOrder(x,200,actor,orders.get(actor,id(match)));return;}
        match=ADMIN_ORDER_AGENTS.matcher(path);
        if(method.equals("GET")&&match.matches()){send(x,200,orders.availableAgents(actor,id(match)));return;}
        match=ADMIN_ORDER_ASSIGN.matcher(path);
        if(method.equals("POST")&&match.matches()){ReassignBody b=read(x,ReassignBody.class);if(b.agentId()==null||b.agentId()<=0)throw ApiException.badRequest("Select an agent");sendOrder(x,200,actor,orders.reassign(actor,id(match),b.agentId(),b.expectedOrderVersion()));return;}
        match=ADMIN_ORDER_STATUS.matcher(path);
        if(method.equals("PATCH")&&match.matches()){sendOrder(x,200,actor,orders.updateStatus(actor,id(match),orderStatus(read(x,StatusBody.class))));return;}
        match=ADMIN_ORDER_CANCEL.matcher(path);
        if(method.equals("POST")&&match.matches()){sendOrder(x,200,actor,orders.updateStatus(actor,id(match),OrderStatus.CANCELLED));return;}
        match=ADMIN_VERIFY_DELIVERY.matcher(path);
        if(method.equals("POST")&&match.matches()){VerifyDeliveryBody b=read(x,VerifyDeliveryBody.class);
            sendVerifiedOrder(x,actor,orders.verifyDelivery(actor,id(match),b.deliveryCode(),b.reason(),remoteIp(x)));return;}
        if(method.equals("GET")&&path.equals("/admin/scheduled-orders")){send(x,200,orders.list(actor).stream().filter(o->o.status()==OrderStatus.SCHEDULED).toList());return;}
        if(method.equals("GET")&&path.equals("/admin/live-deliveries")){send(x,200,tracking.liveDeliveries(actor));return;}
        if(method.equals("GET")&&path.equals("/admin/pricing-analytics")){send(x,200,operations.pricingAnalytics(actor));return;}
        if(method.equals("GET")&&path.equals("/admin/pricing-rules")){send(x,200,pricing.list(actor));return;}
        if(method.equals("GET")&&path.equals("/admin/products")){send(x,200,catalog.products(actor,null,query(x).get("search")));return;}
        if(method.equals("POST")&&path.equals("/admin/categories")){send(x,201,catalog.createCategory(actor,read(x,Category.class)));return;}
        if(method.equals("POST")&&path.equals("/admin/products")){send(x,201,catalog.saveProduct(actor,null,read(x,Product.class)));return;}
        match=ADMIN_PRODUCT.matcher(path);
        if(method.equals("GET")&&match.matches()){send(x,200,catalog.product(actor,id(match)));return;}
        if(method.equals("PUT")&&match.matches()){send(x,200,catalog.saveProduct(actor,id(match),read(x,Product.class)));return;}
        if(method.equals("DELETE")&&match.matches()){send(x,200,catalog.status(actor,id(match),false));return;}
        match=ADMIN_STOCK.matcher(path);
        if(method.equals("PATCH")&&match.matches()){StockBody b=read(x,StockBody.class);send(x,200,catalog.stock(actor,id(match),b.variantId(),b.stockQuantity()));return;}
        match=ADMIN_PRODUCT_STATUS.matcher(path);
        if(method.equals("PATCH")&&match.matches()){ProductStatusBody b=read(x,ProductStatusBody.class);if(b.active()==null)throw ApiException.badRequest("Product status is required");send(x,200,catalog.status(actor,id(match),b.active()));return;}
        match=ADMIN_VARIANT.matcher(path);
        if(method.equals("POST")&&match.matches()){send(x,201,catalog.addVariant(actor,id(match),read(x,ProductVariant.class)));return;}
        match=ADMIN_RULE.matcher(path);
        if(method.equals("PUT")&&match.matches()){send(x,200,pricing.update(actor,id(match),read(x,PricingRule.class)));return;}
        match=REASSIGN.matcher(path);
        if(method.equals("POST")&&match.matches()){ReassignBody b=read(x,ReassignBody.class);if(b.agentId()==null||b.agentId()<=0)throw ApiException.badRequest("Select an agent");
            sendOrder(x,200,actor,orders.reassign(actor,id(match),b.agentId(),b.expectedOrderVersion()));return;}
        throw ApiException.notFound("Endpoint");
    }

    private void sendOrder(HttpExchange x,int status,User actor,Order order)throws IOException,SQLException{
        Map<String,Object> value=new LinkedHashMap<>();value.put("order",order);value.put("route",orders.routeSummary(order));
        value.put("items",orders.items(actor,order.id()));value.put("charges",orders.charges(actor,order.id()));value.put("gift",orders.gift(actor,order.id()).orElse(null));
        String deliveryCode=orders.deliveryCode(actor,order);if(deliveryCode!=null){value.put("deliveryCode",deliveryCode);
            value.put("deliveryCodeMessage","Share this code only when you receive your order.");}send(x,status,value);
    }
    private void sendVerifiedOrder(HttpExchange x,User actor,Order order)throws IOException,SQLException{
        Map<String,Object> value=new LinkedHashMap<>();value.put("success",true);value.put("message","Delivery verified successfully.");
        value.put("order",order);value.put("route",orders.routeSummary(order));value.put("items",orders.items(actor,order.id()));
        value.put("charges",orders.charges(actor,order.id()));value.put("gift",orders.gift(actor,order.id()).orElse(null));send(x,200,value);
    }
    private User requireUser(HttpExchange x){return sessions.resolve(token(x)).orElseThrow(ApiException::unauthorized);}
    private Map<String,Object> login(HttpExchange x,LoginBody body)throws SQLException{
        String ip=x.getRemoteAddress().getAddress()==null?x.getRemoteAddress().getHostString():x.getRemoteAddress().getAddress().getHostAddress();
        String email=body.email()==null?"":body.email().trim().toLowerCase(Locale.ROOT),ipKey="ip:"+ip,accountKey="account:"+email;
        long retry=Math.max(loginLimiter.retryAfter(ipKey,20).orElse(0),loginLimiter.retryAfter(accountKey,5).orElse(0));
        if(retry>0){x.getResponseHeaders().set("Retry-After",String.valueOf(retry));throw new ApiException(429,"Too many login attempts; try again later");}
        try{Map<String,Object> result=auth.login(body.email(),body.password());loginLimiter.succeeded(accountKey);return result;}
        catch(ApiException e){if(e.status()==401){loginLimiter.failed(ipKey);loginLimiter.failed(accountKey);}throw e;}
    }
    private static <T>T read(HttpExchange x,Class<T> type)throws IOException{
        String content=x.getRequestHeaders().getFirst("Content-Type");if(content==null||!content.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new ApiException(415,"Content-Type must be application/json");
        return Json.read(x.getRequestBody(),type);
    }
    private static Map<String,String> query(HttpExchange x){Map<String,String> values=new HashMap<>();String raw=x.getRequestURI().getRawQuery();if(raw==null)return values;
        for(String pair:raw.split("&",-1)){String[] parts=pair.split("=",2);values.put(decode(parts[0]),parts.length==1?"":decode(parts[1]));}return values;}
    private static String decode(String v){return URLDecoder.decode(v,StandardCharsets.UTF_8);}
    private static Long number(String value){if(value==null||value.isBlank())return null;try{long id=Long.parseLong(value);if(id<=0)throw new NumberFormatException();return id;}catch(NumberFormatException e){throw ApiException.badRequest("Category identifier is invalid");}}
    private static String token(HttpExchange x){String h=x.getRequestHeaders().getFirst("Authorization");return h!=null&&h.startsWith("Bearer ")?h.substring(7).trim():null;}
    private static String remoteIp(HttpExchange x){return x.getRemoteAddress().getAddress()==null?x.getRemoteAddress().getHostString():x.getRemoteAddress().getAddress().getHostAddress();}
    private static long id(Matcher matcher){return Long.parseLong(matcher.group(1));}
    private static OrderStatus orderStatus(StatusBody body){try{return OrderStatus.valueOf(body.status()==null?"":body.status().toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e){throw ApiException.badRequest("Unknown order status");}}
    private static void addHeaders(HttpExchange x){x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");
        x.getResponseHeaders().set("X-Content-Type-Options","nosniff");String origin=x.getRequestHeaders().getFirst("Origin");
        if(!ALLOWED_ORIGIN.isBlank()&&ALLOWED_ORIGIN.equals(origin)){x.getResponseHeaders().set("Access-Control-Allow-Origin",origin);x.getResponseHeaders().set("Vary","Origin");}
        x.getResponseHeaders().set("Access-Control-Allow-Headers","Authorization, Content-Type, Idempotency-Key");
        x.getResponseHeaders().set("Access-Control-Allow-Methods","GET, POST, PUT, PATCH, DELETE, OPTIONS");x.getResponseHeaders().set("Referrer-Policy","no-referrer");}
    private static void send(HttpExchange x,int status,Object value)throws IOException{byte[] body=Json.bytes(value);x.sendResponseHeaders(status,body.length);try(OutputStream out=x.getResponseBody()){out.write(body);}}
}
