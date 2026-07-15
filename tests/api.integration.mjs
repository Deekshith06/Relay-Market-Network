import assert from 'node:assert/strict';

const base=process.env.API_BASE||'http://127.0.0.1:8080',password='Demo@123';
async function request(path,{method='GET',token,body,headers={},status=200}={}){
  const response=await fetch(`${base}${path}`,{method,headers:{...(body?{'Content-Type':'application/json'}:{}),...(token?{Authorization:`Bearer ${token}`}:{ }),...headers},body:body?JSON.stringify(body):undefined});
  const text=response.status===204?'':await response.text();let value=null;try{value=text?JSON.parse(text):null}catch{value=text}
  assert.equal(response.status,status,`${method} ${path}: expected ${status}, got ${response.status}: ${text}`);
  return{response,value};
}
async function login(email,p=password){return(await request('/api/auth/login',{method:'POST',body:{email,password:p}})).value;}
async function add(token,productId,{variantId=null,quantity=1,selectedWeightKg=null}={}){
  return(await request('/api/cart/items',{method:'POST',token,status:201,body:{productId,variantId,quantity,selectedWeightKg}})).value;
}
const point=(address,zoneId,latitude,longitude)=>({address,zoneId,latitude,longitude,landmark:null,instructions:'Integration delivery'});
const quoteBody=(cartItems,extra={})=>({cartItems,pickupLocation:point('Relay Market, Church Street',3,12.9757,77.605),deliveryLocation:point('44 HSR Sector Two',4,12.9116,77.6389),priority:'EXPRESS',giftOptions:null,scheduledAt:null,deliveryWindowStart:null,deliveryWindowEnd:null,timezone:'Asia/Kolkata',couponCode:'WELCOME10',...extra});
async function quote(token,body,status=201){return(await request('/api/pricing/quote',{method:'POST',token,body,status})).value;}
async function cartItems(token){return(await request('/api/cart',{token})).value.items.map(x=>({productId:x.productId,variantId:x.variantId,quantity:x.quantity,selectedWeightKg:x.selectedWeightKg}));}
async function clearCart(token){const cart=(await request('/api/cart',{token})).value;for(const item of cart.items)await request(`/api/cart/items/${item.id}`,{method:'DELETE',token});}
async function prepareOrder(adminToken,orderId){
  await request(`/api/admin/orders/${orderId}/status`,{method:'PATCH',token:adminToken,body:{status:'CONFIRMED'}});
  return(await request(`/api/admin/orders/${orderId}/status`,{method:'PATCH',token:adminToken,body:{status:'PACKED'}})).value;
}
async function advanceToVerification(agentToken,orderId){
  for(const status of ['PICKED_UP','OUT_FOR_DELIVERY','DELIVERY_VERIFICATION'])
    await request(`/api/orders/${orderId}/status`,{method:'POST',token:agentToken,body:{status}});
}

const home=await fetch(`${base}/`);assert.equal(home.status,200);
assert.match(home.headers.get('content-security-policy')||'',/images\.unsplash\.com/);
assert.equal(home.headers.get('x-frame-options'),'DENY');
assert.equal((await fetch(`${base}/`,{method:'POST'})).status,405);
await request('/api/auth/login',{method:'OPTIONS',headers:{Origin:'https://untrusted.example'},status:403});
const preflight=await request('/api/auth/login',{method:'OPTIONS',headers:{Origin:'http://localhost:3000'},status:204});
assert.equal(preflight.response.headers.get('access-control-allow-origin'),'http://localhost:3000');

const unknown=`missing-${Date.now()}@relay.demo`;
for(let i=0;i<5;i++)await request('/api/auth/login',{method:'POST',body:{email:unknown,password:'Wrong123'},status:401});
assert.ok(Number((await request('/api/auth/login',{method:'POST',body:{email:unknown,password:'Wrong123'},status:429})).response.headers.get('retry-after'))>0);

const customer=await login('maya@relay.demo'),arjun=await login('arjun@relay.demo'),nila=await login('nila@relay.demo'),admin=await login('admin@relay.demo');
assert.equal(customer.user.role,'CUSTOMER');assert.equal(arjun.user.role,'AGENT');assert.equal(admin.user.role,'ADMIN');
await request('/api/admin/overview',{token:customer.token,status:403});
const outsiderEmail=`outside-${Date.now()}@relay.demo`;
let outsider=(await request('/api/auth/register',{method:'POST',status:201,body:{name:'Outside Customer',email:outsiderEmail,password:'Strong123',role:'CUSTOMER',vehicleType:null}})).value;
await request('/api/auth/login',{method:'POST',body:{email:outsiderEmail,password:'Wrong123'},status:401});
await request('/api/auth/register',{method:'POST',body:{name:'Duplicate Customer',email:outsiderEmail.toUpperCase(),password:'Strong123',role:'CUSTOMER'},status:409});
await request('/api/auth/register',{method:'POST',body:{name:'',email:'bad',password:'short'},status:400});
await request('/api/auth/logout',{method:'POST',token:outsider.token});
await request('/api/cart',{token:outsider.token,status:401});
outsider=await login(`  ${outsiderEmail.toUpperCase()}  `,'Strong123');assert.equal(outsider.user.role,'CUSTOMER');
const outsiderCart=(await request('/api/cart',{token:outsider.token})).value;assert.deepEqual(outsiderCart.items,[]);

const categories=(await request('/api/categories',{token:customer.token})).value;
assert.equal(categories.length,15);assert.ok(categories.some(c=>c.name==='Medicines'));
const products=(await request('/api/products',{token:customer.token})).value;
assert.ok(products.length>=16);assert.notEqual(products[0].unitPrice,products[1].unitPrice);
assert.equal((await request('/api/products?category=8&search=apples',{token:customer.token})).value[0].pricingType,'WEIGHT');
await request('/api/products/1',{token:customer.token});

const productSku=`TEST-SKU-${Date.now()}`,productInput={id:0,categoryId:4,categoryName:'Computer accessories',name:'Integration Cable',description:'A temporary integration-test catalog item.',sku:productSku,pricingType:'FIXED',unitPrice:399,pricePerKg:null,unitWeightKg:.2,minimumOrderWeight:null,maximumOrderWeight:null,packagingWeightKg:.02,stockQuantity:6,fragile:false,temperatureControlled:false,requiresInsurance:false,shelfLifeDays:null,imageUrl:'https://images.unsplash.com/photo-1625842268584-8f3296236761?w=700',active:true,variants:[]};
let managed=(await request('/api/admin/products',{method:'POST',token:admin.token,status:201,body:productInput})).value;
await request('/api/admin/products',{method:'POST',token:admin.token,body:productInput,status:409});
managed=(await request(`/api/admin/products/${managed.id}`,{method:'PUT',token:admin.token,body:{...managed,name:'Integration Cable Updated'}})).value;assert.equal(managed.name,'Integration Cable Updated');
managed=(await request(`/api/admin/products/${managed.id}/stock`,{method:'PATCH',token:admin.token,body:{variantId:null,stockQuantity:9}})).value;assert.equal(Number(managed.stockQuantity),9);
await request(`/api/admin/products/${managed.id}/stock`,{method:'PATCH',token:admin.token,body:{variantId:null,stockQuantity:-1},status:400});
managed=(await request(`/api/admin/products/${managed.id}/status`,{method:'PATCH',token:admin.token,body:{active:false}})).value;assert.equal(managed.active,false);
await request(`/api/products/${managed.id}`,{token:customer.token,status:404});await request('/api/cart/items',{method:'POST',token:customer.token,body:{productId:managed.id,quantity:1},status:404});
managed=(await request(`/api/admin/products/${managed.id}/status`,{method:'PATCH',token:admin.token,body:{active:true}})).value;assert.equal(managed.active,true);
managed=(await request(`/api/admin/products/${managed.id}`,{method:'DELETE',token:admin.token})).value;assert.equal(managed.active,false);

let address=(await request('/api/addresses',{method:'POST',token:customer.token,status:201,body:{label:'OTHER',addressLine:'88 Integration Test Road',landmark:'Test tower',instructions:'Desk 4',latitude:12.92,longitude:77.64,zoneId:4,isDefault:false}})).value;
address=(await request(`/api/addresses/${address.id}`,{method:'PUT',token:customer.token,body:{label:'WORK',addressLine:'89 Integration Test Road',landmark:'Test tower',instructions:'Desk 5',latitude:12.92,longitude:77.64,zoneId:4,isDefault:false}})).value;
assert.equal(address.label,'WORK');await request(`/api/addresses/${address.id}`,{method:'DELETE',token:customer.token});

await clearCart(customer.token);
let weighted=await add(customer.token,9,{selectedWeightKg:1});weighted=await add(customer.token,9,{selectedWeightKg:1});
assert.equal(weighted.items.length,1);assert.equal(weighted.items[0].quantity,1);assert.equal(Number(weighted.items[0].selectedWeightKg),2);
const weightQuote=await quote(customer.token,quoteBody(await cartItems(customer.token),{couponCode:null,priority:'STANDARD'}));assert.equal(Number(weightQuote.productSubtotal),310);
await clearCart(customer.token);
await add(customer.token,1);await add(customer.token,2,{variantId:1});await add(customer.token,8,{selectedWeightKg:.5});
const mixedItems=await cartItems(customer.token),mixedQuote=await quote(customer.token,quoteBody(mixedItems));
assert.equal(Number(mixedQuote.productSubtotal),51088);assert.equal(Number(mixedQuote.totalWeightKg),1.56);
assert.ok(Number(mixedQuote.distanceCharge)>0);assert.ok(Number(mixedQuote.weightCharge)>0);
assert.ok(mixedQuote.categoryCharges.length>=3);assert.ok(mixedQuote.specialHandlingCharges.some(x=>x.type==='INSURANCE'));
assert.ok(Number(mixedQuote.discount)>0);assert.ok(Number(mixedQuote.finalPayableAmount)>Number(mixedQuote.productSubtotal));

await request('/api/orders',{method:'POST',token:customer.token,headers:{'Idempotency-Key':`tamper-${Date.now()}`},body:{quoteId:mixedQuote.quoteId,finalPayableAmount:1},status:400});
const orderKey=`marketplace-${Date.now()}`;
const placed=(await request('/api/orders',{method:'POST',token:customer.token,status:201,headers:{'Idempotency-Key':orderKey},body:{quoteId:mixedQuote.quoteId}})).value;
const duplicate=(await request('/api/orders',{method:'POST',token:customer.token,status:201,headers:{'Idempotency-Key':orderKey},body:{quoteId:mixedQuote.quoteId}})).value;
assert.equal(duplicate.order.id,placed.order.id);assert.equal(placed.items.length,3);assert.equal(placed.order.status,'PLACED');
assert.match(placed.deliveryCode,/^[0-9]{6}$/);assert.equal(placed.deliveryCodeMessage,'Share this code only when you receive your order.');
assert.equal(Object.hasOwn((await request(`/api/admin/orders/${placed.order.id}`,{token:admin.token})).value,'deliveryCode'),false);
await request(`/api/admin/orders/${placed.order.id}/status`,{method:'PATCH',token:admin.token,body:{status:'ASSIGNED'},status:409});
await request(`/api/orders/${placed.order.id}/status`,{method:'POST',token:customer.token,body:{status:'DELIVERED'},status:403});
const prepared=await prepareOrder(admin.token,placed.order.id);assert.equal(prepared.order.status,'ASSIGNED');
await request('/api/orders',{method:'POST',token:customer.token,headers:{'Idempotency-Key':`quote-reuse-${Date.now()}`},body:{quoteId:mixedQuote.quoteId},status:409});
assert.equal(Number((await request('/api/products/1',{token:customer.token})).value.stockQuantity),24);
assert.equal(Number((await request('/api/products/2',{token:customer.token})).value.variants.find(v=>v.id===1).stockQuantity),7);
assert.equal(Number((await request('/api/products/8',{token:customer.token})).value.stockQuantity),79.5);
await request(`/api/orders/${placed.order.id}`,{token:outsider.token,status:403});

await add(customer.token,4);const changedItems=await cartItems(customer.token),priceQuote=await quote(customer.token,quoteBody(changedItems,{couponCode:null}));
const hub=(await request('/api/products/4',{token:admin.token})).value,changed={...hub,unitPrice:Number(hub.unitPrice)+1};
await request('/api/admin/products/4',{method:'PUT',token:admin.token,body:changed});
await request('/api/orders',{method:'POST',token:customer.token,headers:{'Idempotency-Key':`changed-${Date.now()}`},body:{quoteId:priceQuote.quoteId},status:409});
await request('/api/admin/products/4',{method:'PUT',token:admin.token,body:hub});await clearCart(customer.token);
await quote(customer.token,quoteBody([{productId:4,variantId:null,quantity:1,selectedWeightKg:null}],{couponCode:'NOTREAL'}),400);

const activeAgent=prepared.order.agentName===nila.user.name?nila:arjun,wrongAgent=activeAgent===nila?arjun:nila,recordedAt=new Date().toISOString();
assert.equal(Object.hasOwn((await request(`/api/orders/${placed.order.id}`,{token:activeAgent.token})).value,'deliveryCode'),false);
await request('/api/agents/me/location',{method:'POST',token:wrongAgent.token,status:403,body:{orderId:placed.order.id,latitude:12.94,longitude:77.63,accuracyMeters:12,heading:90,speedMetersPerSecond:5,recordedAt}});
await request('/api/agents/me/location',{method:'POST',token:activeAgent.token,status:400,body:{orderId:placed.order.id,latitude:91,longitude:77,accuracyMeters:12,heading:90,speedMetersPerSecond:5,recordedAt}});
await request('/api/agents/me/location',{method:'POST',token:activeAgent.token,status:202,body:{orderId:placed.order.id,latitude:12.94,longitude:77.63,accuracyMeters:12,heading:90,speedMetersPerSecond:5,recordedAt}});
await request('/api/agents/me/location',{method:'POST',token:activeAgent.token,status:409,body:{orderId:placed.order.id,latitude:12.94,longitude:77.63,accuracyMeters:12,heading:90,speedMetersPerSecond:5,recordedAt}});
const tracking=(await request(`/api/orders/${placed.order.id}/tracking`,{token:customer.token})).value;
assert.equal(tracking.location.orderId,placed.order.id);assert.ok(tracking.eta.minimumMinutes<tracking.eta.maximumMinutes);
assert.equal(tracking.deliveryCode,placed.deliveryCode);assert.match(tracking.agent.maskedContact,/\d{4}$/);await request(`/api/orders/${placed.order.id}/tracking`,{token:outsider.token,status:403});
await request(`/api/orders/${placed.order.id}/status`,{method:'POST',token:activeAgent.token,body:{status:'DELIVERED'},status:409});
await request(`/api/admin/orders/${placed.order.id}/status`,{method:'PATCH',token:admin.token,body:{status:'DELIVERED'},status:409});
await advanceToVerification(activeAgent.token,placed.order.id);
await request(`/api/orders/${placed.order.id}/verify-delivery`,{method:'POST',token:wrongAgent.token,body:{deliveryCode:placed.deliveryCode},status:403});
const wrongCode=placed.deliveryCode==='000000'?'000001':'000000';
await request(`/api/orders/${placed.order.id}/verify-delivery`,{method:'POST',token:activeAgent.token,body:{deliveryCode:wrongCode},status:400});
const verified=(await request(`/api/orders/${placed.order.id}/verify-delivery`,{method:'POST',token:activeAgent.token,body:{deliveryCode:placed.deliveryCode}})).value;
assert.equal(verified.success,true);assert.equal(verified.order.status,'DELIVERED');assert.ok(verified.order.deliveredAt);
await request(`/api/orders/${placed.order.id}/verify-delivery`,{method:'POST',token:activeAgent.token,body:{deliveryCode:placed.deliveryCode},status:409});
const deliveredTracking=(await request(`/api/orders/${placed.order.id}/tracking`,{token:customer.token})).value;
assert.equal(deliveredTracking.sharing,false);assert.equal(Object.hasOwn(deliveredTracking,'deliveryCode'),false);

await request('/api/agents/me/status',{method:'POST',token:arjun.token,body:{status:'OFFLINE'}});await request('/api/agents/me/status',{method:'POST',token:nila.token,body:{status:'OFFLINE'}});
const manualQuote=await quote(customer.token,quoteBody([{productId:4,variantId:null,quantity:1,selectedWeightKg:null}],{couponCode:null,priority:'STANDARD'}));
const manualOrder=(await request('/api/orders',{method:'POST',token:customer.token,status:201,headers:{'Idempotency-Key':`manual-${Date.now()}`},body:{quoteId:manualQuote.quoteId}})).value;assert.equal(manualOrder.order.status,'PLACED');assert.match(manualOrder.deliveryCode,/^[0-9]{6}$/);assert.notEqual(manualOrder.deliveryCode,placed.deliveryCode);
const queuedManual=await prepareOrder(admin.token,manualOrder.order.id);assert.equal(queuedManual.order.status,'PACKED');
const arjunAgent=(await request('/api/agents/me',{token:arjun.token})).value;await request('/api/agents/me/status',{method:'POST',token:arjun.token,body:{status:'AVAILABLE'}});
const staleRequest={agentId:arjunAgent.id,expectedOrderVersion:queuedManual.order.version};
await request(`/api/admin/orders/${manualOrder.order.id}/assign`,{method:'POST',token:admin.token,body:staleRequest,status:409});
const assignedAfterDispatch=(await request(`/api/admin/orders/${manualOrder.order.id}`,{token:admin.token})).value.order;assert.equal(assignedAfterDispatch.status,'ASSIGNED');assert.equal(assignedAfterDispatch.agentId,arjunAgent.id);
const nilaAgent=(await request('/api/agents/me',{token:nila.token})).value;await request('/api/agents/me/status',{method:'POST',token:nila.token,body:{status:'AVAILABLE'}});
const candidates=(await request(`/api/admin/orders/${manualOrder.order.id}/available-agents`,{token:admin.token})).value;assert.ok(candidates.some(agent=>agent.id===nilaAgent.id));
const manualRequest={agentId:nilaAgent.id,expectedOrderVersion:assignedAfterDispatch.version};
const manuallyAssigned=(await request(`/api/admin/orders/${manualOrder.order.id}/reassign`,{method:'POST',token:admin.token,body:manualRequest})).value;assert.equal(manuallyAssigned.order.status,'ASSIGNED');assert.equal(manuallyAssigned.order.agentId,nilaAgent.id);
await request(`/api/admin/orders/${manualOrder.order.id}/reassign`,{method:'POST',token:admin.token,body:manualRequest,status:409});
await request(`/api/admin/orders/${manualOrder.order.id}/status`,{method:'PATCH',token:admin.token,body:{status:'DELIVERED'},status:409});
await advanceToVerification(nila.token,manualOrder.order.id);
await request(`/api/admin/orders/${manualOrder.order.id}/verify-delivery`,{method:'POST',token:admin.token,body:{deliveryCode:manualOrder.deliveryCode,reason:'Customer requested operations confirmation at the door'}});

await add(customer.token,15);const giftItems=await cartItems(customer.token);
const future=new Date(Date.now()+3*86400000),parts=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(future),part=type=>parts.find(x=>x.type===type).value,slotDate=`${part('year')}-${part('month')}-${part('day')}`;
const slots=(await request(`/api/delivery/slots?date=${slotDate}&timezone=Asia%2FKolkata`,{token:customer.token})).value;assert.ok(slots.length>0);
const scheduledAt=`${slotDate}T09:30:00+05:30`,giftOptions={enabled:true,recipientName:'Asha Rao',recipientPhone:'+919900001234',occasion:'BIRTHDAY',giftMessage:'A bright year ahead',senderName:'Maya',hideSender:true,hidePrice:true,wrappingStyle:'PREMIUM',cardStyle:'CELEBRATION',surpriseDelivery:true,recipientOtpRequired:true,deliveryInstructions:'Call only at the gate'};
const giftQuote=await quote(customer.token,quoteBody(giftItems,{giftOptions,scheduledAt,deliveryWindowStart:null,deliveryWindowEnd:null,couponCode:null}));
assert.equal(Number(giftQuote.giftCharge),99);assert.ok(Number(giftQuote.schedulingCharge)>0);
const giftOrder=(await request('/api/orders',{method:'POST',token:customer.token,status:201,headers:{'Idempotency-Key':`gift-${Date.now()}`},body:{quoteId:giftQuote.quoteId}})).value;
assert.equal(giftOrder.order.status,'SCHEDULED');assert.equal(giftOrder.order.orderType,'GIFT');assert.equal(giftOrder.gift.hidePrice,true);
assert.ok((await request('/api/admin/scheduled-orders',{token:admin.token})).value.some(o=>o.id===giftOrder.order.id));
await request(`/api/orders/${giftOrder.order.id}/status`,{method:'POST',token:customer.token,body:{status:'CANCELLED'}});
assert.equal(Number((await request('/api/products/15',{token:customer.token})).value.stockQuantity),20);

await add(customer.token,15);const recoveryQuote=await quote(customer.token,quoteBody(await cartItems(customer.token),{giftOptions,scheduledAt,deliveryWindowStart:null,deliveryWindowEnd:null,couponCode:null}));
const recoveryGift=(await request('/api/orders',{method:'POST',token:customer.token,status:201,headers:{'Idempotency-Key':`recovery-gift-${Date.now()}`},body:{quoteId:recoveryQuote.quoteId}})).value;
assert.equal(recoveryGift.order.status,'SCHEDULED');

await add(customer.token,12,{quantity:8});const heavyQuote=await quote(customer.token,quoteBody(await cartItems(customer.token),{couponCode:null,priority:'STANDARD'}));
assert.ok(Number(heavyQuote.totalWeightKg)>20);const heavyOrder=(await request('/api/orders',{method:'POST',token:customer.token,status:201,headers:{'Idempotency-Key':`heavy-${Date.now()}`},body:{quoteId:heavyQuote.quoteId}})).value;
assert.equal(heavyOrder.order.status,'PLACED');await request(`/api/orders/${heavyOrder.order.id}/assign`,{method:'POST',token:admin.token,status:409});
const packedHeavy=await prepareOrder(admin.token,heavyOrder.order.id);assert.equal(packedHeavy.order.status,'PACKED');
await request(`/api/orders/${heavyOrder.order.id}/assign`,{method:'POST',token:admin.token,status:409});
assert.deepEqual((await request(`/api/admin/orders/${heavyOrder.order.id}/available-agents`,{token:admin.token})).value,[]);
await request(`/api/admin/orders/${heavyOrder.order.id}/assign`,{method:'POST',token:admin.token,body:{agentId:arjunAgent.id,expectedOrderVersion:packedHeavy.order.version},status:409});

await add(customer.token,4);const lockQuote=await quote(customer.token,quoteBody(await cartItems(customer.token),{couponCode:null,priority:'STANDARD'}));
const lockOrder=(await request('/api/orders',{method:'POST',token:customer.token,status:201,headers:{'Idempotency-Key':`lockout-${Date.now()}`},body:{quoteId:lockQuote.quoteId}})).value;
const lockPrepared=await prepareOrder(admin.token,lockOrder.order.id),lockAgent=lockPrepared.order.agentName===nila.user.name?nila:arjun;
await advanceToVerification(lockAgent.token,lockOrder.order.id);
await request(`/api/orders/${lockOrder.order.id}/verify-delivery`,{method:'POST',token:customer.token,body:{deliveryCode:lockOrder.deliveryCode},status:403});
await request(`/api/admin/orders/${lockOrder.order.id}/verify-delivery`,{method:'POST',token:admin.token,body:{deliveryCode:lockOrder.deliveryCode,reason:''},status:400});
const lockWrong=lockOrder.deliveryCode==='111111'?'111112':'111111';
const failures=['',lockWrong,lockWrong,lockWrong];
for(const deliveryCode of failures){const failed=await request(`/api/orders/${lockOrder.order.id}/verify-delivery`,{method:'POST',token:lockAgent.token,body:{deliveryCode},status:400});assert.match(failed.value.error,/Incorrect delivery code/)}
await request(`/api/orders/${lockOrder.order.id}/verify-delivery`,{method:'POST',token:lockAgent.token,body:{deliveryCode:lockWrong},status:429});
await request(`/api/orders/${lockOrder.order.id}/verify-delivery`,{method:'POST',token:lockAgent.token,body:{deliveryCode:lockOrder.deliveryCode},status:429});

const rule=(await request('/api/admin/pricing-rules',{token:admin.token})).value.find(r=>r.type==='PLATFORM_FEE');
await request(`/api/admin/pricing-rules/${rule.id}`,{method:'PUT',token:admin.token,body:{...rule,flatAmount:Number(rule.flatAmount)+1}});
await request(`/api/admin/pricing-rules/${rule.id}`,{method:'PUT',token:admin.token,body:rule});
await request('/api/admin/pricing-analytics',{token:admin.token});const overview=(await request('/api/admin/overview',{token:admin.token})).value;
assert.ok(overview.products.length>=16);assert.ok(overview.pricingRules.length>20);

console.log(JSON.stringify({catalog:`${categories.length} categories / ${products.length} products`,normalizedRelogin:outsiderEmail,weightedCart:`${weightQuote.productSubtotal} for 2 kg`,managedProduct:managed.id,mixedQuote:mixedQuote.quoteId,idempotentOrder:placed.order.id,deliveryVerification:'customer-only code, courier verification and admin reason exercised',manualAssignment:manualOrder.order.id,tracking:'authorized and stopped',scheduledGift:giftOrder.order.id,restartScheduled:recoveryGift.order.id,capacityQueued:heavyOrder.order.id,verificationLockout:lockOrder.order.id,admin:'catalog, stock, assignment and pricing audit exercised'},null,2));
