package com.petplatform.verification.biz.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.order.api.query.OrderVerificationCredentialFactsApi;
import com.petplatform.order.api.query.OrderVerificationCredentialFactsApi.*;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.verification.api.command.*;
import com.petplatform.verification.biz.infrastructure.persistence.CredentialStore;
import com.petplatform.verification.biz.infrastructure.persistence.mapper.CredentialMapper;
import com.petplatform.verification.biz.infrastructure.persistence.mapper.CredentialMapper.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
/** Real durable credentials. No HTTP, merchant service completion or fake authorization adapter. */
public final class VerificationCredentialService implements VerificationCredentialApi,VerificationRescheduleFenceApi {
 private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();static final ObjectMapper JSON=new ObjectMapper();
 final DataSource source;final CredentialMapper db;final SnowflakeIdGenerator ids;final ScheduleCapacityGuardApi guard;
 final OrderVerificationCredentialFactsApi orders;final CredentialProtection protection;final CredentialPorts.Sessions sessions;
 final CredentialPorts.AttemptAuthority authority;final IntegrationEventPublisher outbox;final TransactionTemplate tx;
 public VerificationCredentialService(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,OrderVerificationCredentialFactsApi orders,
   CredentialProtection protection,CredentialPorts.Sessions sessions,CredentialPorts.AttemptAuthority authority,IntegrationEventPublisher outbox){
  this.source=Objects.requireNonNull(source);this.ids=Objects.requireNonNull(ids);this.guard=Objects.requireNonNull(guard);this.orders=Objects.requireNonNull(orders);
  this.protection=Objects.requireNonNull(protection);this.sessions=Objects.requireNonNull(sessions);this.authority=Objects.requireNonNull(authority);this.outbox=Objects.requireNonNull(outbox);db=CredentialStore.mapper(source);
  tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(15);
 }
 public Receipt issue(Issue c){return safe(()->{
  if(c==null)throw invalid();validate(c.context(),c.orderId(),true);long version=version(c.expectedCredentialVersion());
  if(!Set.of("INITIAL","AUTO","MANUAL").contains(c.refreshKind()==null?"":c.refreshKind()))throw invalid();top();
  tx.executeWithoutResult(s->consumer(c.orderId(),query(c.context())));
  var key=key("verification.issue",c.context(),"CONSUMER");String purpose=purpose(key);
  var input=json(values("orderId",c.orderId(),"version",c.expectedCredentialVersion(),"kind",c.refreshKind()));admit(key,purpose,input);
  return tx.execute(s->{defaults();var b=db.binding(key);same(b,purpose,input);var q=query(c.context());var loc=consumer(c.orderId(),q);
   if("SUCCEEDED".equals(b.state))return result(b,purpose,Receipt.class);reserved(b);
   var f=orders.requireEligible(c.orderId(),loc.storeId(),system(q));if(!c.context().operatorId().equals(f.location().userId()))throw error(CommonApiCodes.FORBIDDEN);var st=state(f.location(),true);var now=db.now();var old=current(st,f);
   if(st.epoch!=f.confirmRound())throw bad();if(locked(st,now))throw error("VERIFICATION_RISK_LOCKED");if(st.version!=version)throw error(CommonApiCodes.CONFLICT);
   if("INITIAL".equals(c.refreshKind())&&old!=null||"AUTO".equals(c.refreshKind())&&(old==null||now.isBefore(old.expiresAt)))throw error(CommonApiCodes.CONFLICT);
   if("MANUAL".equals(c.refreshKind())&&db.refreshCount(st.orderId,IDS.fromApi(loc.userId()),now.minusSeconds(60))>=5)throw error(CommonApiCodes.RATE_LIMITED);
   long id=next(),generation=Math.addExact(st.version,1);String code=protection.generate();var v=values("id",id,"order",st.orderId,"merchant",IDS.fromApi(f.location().merchantId()),"store",st.storeId,"reservation",st.reservationId,
    "epoch",st.epoch,"version",generation,"oldVersion",st.version,"credential",id,"round",f.confirmRound(),"start",local(f.appointmentStart()),"end",local(f.appointmentEnd()),"pickup",local(f.pickupStart()),"returning",local(f.returnStart()),
    "keyId",protection.keyId(),"hash",protection.digest(protection.keyId(),code),"cipher",protection.protect("CODE:"+id,bytes(code)),"now",now,"expiry",now.plusMinutes(5),"reason","REFRESH",
    "refreshId",next(),"user",IDS.fromApi(loc.userId()),"command",b.id,"kind",c.refreshKind());
   db.invalidate(v);one(db.insertCode(v));one(db.advance(v));one(db.refresh(v));
   var receipt=new Receipt(c.orderId(),Long.toString(id),Long.toString(generation),code,time(now),time(now.plusMinutes(5)),time(now.plusMinutes(5)));finish(b,purpose,receipt);return receipt;
  });
 });}
 public View read(String order,QueryContext q){return safe(()->{
  validateQuery(q,order);top();return tx.execute(s->{var loc=consumer(order,q);var f=orders.requireEligible(order,loc.storeId(),system(q));if(!q.operatorId().equals(f.location().userId()))throw error(CommonApiCodes.FORBIDDEN);var st=state(f.location(),false);var code=current(st,f);var now=db.now();
   if(st.epoch!=f.confirmRound())throw bad();String status=locked(st,now)?"LOCKED":code==null?(st.epoch==0?"NONE":"INVALIDATED"):!now.isBefore(code.expiresAt)?"EXPIRED":"ACTIVE";
   return new View(order,Long.toString(st.version),status,"ACTIVE".equals(status)?plain(code):null,code==null?null:time(code.expiresAt),code==null?null:time(code.expiresAt),time(st.lockedUntil));
  });
 });}
 public CheckResult check(Check c){return safe(()->{
  if(c==null)throw invalid();validate(c.context(),c.orderId(),false);IDS.fromApi(c.storeId());
  if(c.verificationCode()==null||!c.verificationCode().matches("[0-9A-Z]{1,128}"))throw invalid();top();
  tx.executeWithoutResult(s->attemptLocation(c));var key=key("verification.check",c.context(),"STORE:"+c.storeId());String purpose=purpose(key);
  var input=json(values("orderId",c.orderId(),"storeId",c.storeId(),"code",c.verificationCode()));admit(key,purpose,input);
  return tx.execute(s->{defaults();var b=db.binding(key);same(b,purpose,input);var loc=attemptLocation(c);
   if("SUCCEEDED".equals(b.state))return result(b,purpose,CheckResult.class);reserved(b);
   var f=orders.requireEligible(c.orderId(),c.storeId(),system(query(c.context())));var st=state(f.location(),true);var now=db.now();var current=current(st,f);
   var receipt=assess(c,b,st,f,now,current);finish(b,purpose,receipt);return receipt;
  });
 });}
 // Shared only inside VER: caller owns the command transaction and current ORDER facts.
 CheckResult assess(Check c,Binding b,State st,Fact f,LocalDateTime now,Code current){
   if(st.epoch!=f.confirmRound())throw bad();String result="VALID";boolean failed=false;
   if(locked(st,now))result="VERIFICATION_RISK_LOCKED";
   else if(current==null||!MessageDigest.isEqual(bytes(current.lookupHash),bytes(protection.digest(current.lookupKeyId,c.verificationCode())))){result="VERIFICATION_CODE_INVALID";failed=true;}
   else if(!now.isBefore(current.expiresAt)){result="VERIFICATION_CODE_EXPIRED";failed=true;}
   long attempt=next();boolean newLock=failed&&db.failureCount(st.orderId,now.minusMinutes(5))>=2;
   if(newLock){result="VERIFICATION_RISK_LOCKED";one(db.riskLock(values("order",st.orderId,"now",now,"until",now.plusMinutes(15))));
    outbox.publish(new IntegrationEvent<>(Long.toString(next()),"VerificationRiskLockedEvent.v1",1,now.atOffset(ZoneOffset.UTC),"VERIFICATION",c.orderId(),c.context().traceId(),
     values("orderId",c.orderId(),"storeId",c.storeId(),"triggerAttemptId",Long.toString(attempt),"lockedAt",time(now),"lockedUntil",time(now.plusMinutes(15)),"reasonCode","INVALID_CREDENTIAL_THRESHOLD")));
   }
   one(db.attempt(values("id",attempt,"order",st.orderId,"store",st.storeId,"credential",current==null?null:current.id,"command",b.id,"actorType",c.context().operatorType().name(),"actor",IDS.fromApi(c.context().operatorId()),"result",result,"failed",failed,"now",now)));
   return new CheckResult(c.orderId(),Long.toString(attempt),result,time(now));
 }
 public VerificationRescheduleFenceApi.Fence invalidate(String order,String reservation,String store,String change,OffsetDateTime at,CommandContext c,DataSource transactionSource){
  try{
   validate(c,order,true);IDS.fromApi(reservation);IDS.fromApi(store);IDS.fromApi(change);PublicContractChecks.requireMillisecondPrecision(at);
   if(source!=transactionSource||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
    ||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder))throw bad();guard.requireHeld(store,source);defaults();
   sessions.requireCurrent(c.operatorId());var q=system(query(c));var existing=db.fence(IDS.fromApi(order));
   if(existing!=null){
    if(!change.equals(str(existing.rescheduleId))||!reservation.equals(str(existing.reservationId))||!store.equals(str(existing.storeId))||!c.operatorId().equals(str(existing.userId))||!at.isEqual(existing.rescheduledAt.atOffset(ZoneOffset.UTC)))throw error(CommonApiCodes.CONFLICT);
    orders.requireRescheduleCommitted(order,change,str(existing.id),at,q);return new VerificationRescheduleFenceApi.Fence(str(existing.id),order,change);
   }
   var loc=orders.requireRescheduleSource(order,reservation,store,c.operatorId(),q);var st=state(loc,true);if(st.epoch!=0)throw bad();
   var previous=st.currentCredentialId==null?null:db.code(st.currentCredentialId);if(st.currentCredentialId!=null&&(previous==null||previous.invalidatedAt!=null||!Objects.equals(previous.orderId,st.orderId)||!Objects.equals(previous.epoch,st.epoch)))throw bad();
   long id=next();var v=values("id",id,"order",st.orderId,"reservation",st.reservationId,"store",st.storeId,"user",IDS.fromApi(loc.userId()),"change",IDS.fromApi(change),"oldEpoch",st.epoch,"epoch",1L,
    "generation",previous==null?null:previous.generation,"now",at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),"reason","RESCHEDULE","credential",null,"oldVersion",st.version);
   db.invalidate(v);one(db.advance(v));one(db.insertFence(v));
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean readOnly){
    guard.requireHeld(store,source);if(readOnly)throw bad();sessions.requireCurrent(c.operatorId());orders.requireRescheduleCommitted(order,change,Long.toString(id),at,q);
    var finalState=db.state(st.orderId);var proof=db.fence(st.orderId);if(finalState==null||finalState.epoch!=1||finalState.version!=st.version+1||finalState.currentCredentialId!=null||db.liveCount(st.orderId)!=0||proof==null||proof.id!=id)throw bad();
   }});
   return new VerificationRescheduleFenceApi.Fence(Long.toString(id),order,change);
  }catch(RuntimeException e){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();if(e instanceof ApiException a)throw a;throw bad();}
 }
 private Location consumer(String order,QueryContext q){
  defaults();validateQuery(q,order);sessions.requireCurrent(q.operatorId());var loc=orders.locate(order,system(q));
  if(loc==null||!q.operatorId().equals(loc.userId()))throw error(CommonApiCodes.FORBIDDEN);guard.acquire(List.of(loc.storeId()),system(q));guard.requireHeld(loc.storeId(),source);sessions.requireCurrent(q.operatorId());return loc;
 }
 Location attemptLocation(Check c){
  defaults();var q=system(query(c.context()));var loc=orders.locate(c.orderId(),q);if(loc==null||!c.storeId().equals(loc.storeId()))throw error("VERIFICATION_STORE_MISMATCH");
  guard.acquire(List.of(c.storeId()),q);guard.requireHeld(c.storeId(),source);
  authority.requireAuthorized(c.context(),loc.merchantId(),c.storeId());return loc;
 }
 State state(Location loc,boolean create){
  var st=db.state(IDS.fromApi(loc.orderId()));if(st==null){if(db.historyCount(IDS.fromApi(loc.orderId()))!=0)throw bad();
   st=new State();st.orderId=IDS.fromApi(loc.orderId());st.storeId=IDS.fromApi(loc.storeId());st.reservationId=IDS.fromApi(loc.reservationId());st.epoch=0L;st.version=0L;
   if(create)one(db.initialize(values("order",st.orderId,"store",st.storeId,"reservation",st.reservationId)));
  }
  if(st.epoch==null||st.epoch<0||st.epoch>1||st.version==null||st.version<0||!loc.storeId().equals(str(st.storeId))||!loc.reservationId().equals(str(st.reservationId)))throw bad();
  if(st.epoch==0&&st.version>0&&st.currentCredentialId==null)throw bad();
  if(db.liveCount(st.orderId)!=(st.currentCredentialId==null?0:1))throw bad();
  var fence=db.fence(st.orderId);if(st.epoch==0&&fence!=null||st.epoch==1&&(fence==null||fence.newEpoch!=1||!loc.storeId().equals(str(fence.storeId))||!loc.reservationId().equals(str(fence.reservationId))))throw bad();
  if(fence!=null)orders.requireRescheduleCommitted(loc.orderId(),str(fence.rescheduleId),str(fence.id),fence.rescheduledAt.atOffset(ZoneOffset.UTC),new QueryContext("credential-state",OperatorType.SYSTEM,null));return st;
 }
 Code current(State st,Fact f){
  if(st.currentCredentialId==null)return null;var c=db.code(st.currentCredentialId);
  if(c==null||!Objects.equals(c.orderId,st.orderId)||!Objects.equals(c.storeId,st.storeId)||!Objects.equals(c.reservationId,st.reservationId)||!Objects.equals(c.epoch,st.epoch)
   ||!str(c.merchantId).equals(f.location().merchantId())||c.confirmRound==null||c.confirmRound!=f.confirmRound()||!Objects.equals(c.generation,st.version)
   ||c.invalidatedAt!=null||c.invalidatedReason!=null||!Objects.equals(c.appointmentStart,local(f.appointmentStart()))||!Objects.equals(c.appointmentEnd,local(f.appointmentEnd()))
   ||!Objects.equals(c.pickupStart,local(f.pickupStart()))||!Objects.equals(c.returnStart,local(f.returnStart()))||c.issuedAt==null||c.expiresAt==null||!c.issuedAt.plusMinutes(5).equals(c.expiresAt))throw bad();
  plain(c);return c;
 }
 private String plain(Code c){String value=new String(protection.reveal("CODE:"+c.id,c.codeCipher),StandardCharsets.UTF_8);
  if(!value.matches("[0-9A-Z]{32}")||!MessageDigest.isEqual(bytes(c.lookupHash),bytes(protection.digest(c.lookupKeyId,value))))throw bad();return value;}
 void admit(Map<String,Object> key,String purpose,byte[] input){tx.executeWithoutResult(s->{defaults();var v=new LinkedHashMap<>(key);v.put("id",next());v.put("hash",sha(input));v.put("canonical",protection.protect(purpose,input));db.reserve(v);same(db.binding(key),purpose,input);});}
 void same(Binding b,String purpose,byte[] input){if(b==null||!"canonical-v1".equals(b.canonicalVersion))throw bad();if(!sha(input).equals(b.payloadSha256)||!MessageDigest.isEqual(input,protection.reveal(purpose,b.canonicalBytes)))throw error(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT);}
 void finish(Binding b,String purpose,Object result){one(db.succeed(b.id,protection.protect(purpose+":RESULT",json(result))));}
 private <T>T result(Binding b,String purpose,Class<T> type){try{if(!Objects.equals(b.resultVersion,1)||b.resultBytes==null)throw bad();T receipt=JSON.readValue(protection.reveal(purpose+":RESULT",b.resultBytes),type);
  if(type==Receipt.class){Long id=db.receiptCode(b.id);var c=id==null?null:db.code(id);if(c==null)throw bad();
   var expected=new Receipt(str(c.orderId),str(c.id),str(c.generation),plain(c),time(c.issuedAt),time(c.expiresAt),time(c.expiresAt));if(!expected.equals(receipt))throw bad();
  }else if(type==CheckResult.class){var a=db.receiptAttempt(b.id);if(a==null||!new CheckResult(str(a.orderId),str(a.id),a.resultCode,time(a.attemptedAt)).equals(receipt))throw bad();}
  else throw bad();return receipt;}catch(Exception e){throw bad();}}
 static void reserved(Binding b){if(!"RESERVED".equals(b.state))throw bad();}
 static Map<String,Object> key(String namespace,CommandContext c,String scope){return values("namespace",bytes(namespace),"actorType",bytes(c.operatorType().name()),"actor",IDS.fromApi(c.operatorId()),"scope",bytes(scope),"requestId",bytes(c.requestId()));}
 static String purpose(Map<String,Object> k){return "VC:"+sha(json(k));}
 void defaults(){db.utc();db.timeout();}
 static void top(){if(TransactionSynchronizationManager.isActualTransactionActive())throw bad();}
 static void validate(CommandContext c,String order,boolean user){try{if(c==null||c.operatorId()==null||user&&c.operatorType()!=OperatorType.USER||!user&&!Set.of(OperatorType.USER,OperatorType.MERCHANT_STAFF).contains(c.operatorType()))throw invalid();IDS.fromApi(order);IDS.fromApi(c.operatorId());PublicContractChecks.requireTerminalRequestId(c.requestId());trace(c.traceId());}catch(RuntimeException e){throw invalid();}}
 private static void validateQuery(QueryContext q,String order){try{if(q==null||q.operatorType()!=OperatorType.USER)throw invalid();IDS.fromApi(order);IDS.fromApi(q.operatorId());trace(q.traceId());}catch(RuntimeException e){throw invalid();}}
 private static void trace(String t){if(t==null||t.isBlank()||t.length()>64||t.codePoints().anyMatch(Character::isISOControl))throw invalid();}
 static long version(String v){try{if(v==null||!v.matches("0|[1-9][0-9]*"))throw invalid();return Long.parseLong(v);}catch(RuntimeException e){throw invalid();}}
 private static boolean locked(State s,LocalDateTime now){return s.lockedUntil!=null&&now.isBefore(s.lockedUntil);}
 long next(){long n=ids.nextId();if(n<=0)throw bad();return n;}
 static void one(int n){if(n!=1)throw bad();}
 static QueryContext query(CommandContext c){return new QueryContext(c.traceId(),c.operatorType(),c.operatorId());}
 static QueryContext system(QueryContext c){return new QueryContext(c.traceId(),OperatorType.SYSTEM,null);}
 static String str(Long n){if(n==null)throw bad();return Long.toString(n);}
 static String time(LocalDateTime t){return t==null?null:t.atOffset(ZoneOffset.UTC).toString();}
 static LocalDateTime local(String t){return t==null?null:OffsetDateTime.parse(t).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}
 static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static byte[] json(Object v){try{return JSON.writeValueAsBytes(v);}catch(Exception e){throw bad();}}
 static String sha(byte[] v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v));}catch(Exception e){throw bad();}}
 static Map<String,Object> values(Object...v){var m=new LinkedHashMap<String,Object>();for(int i=0;i<v.length;i+=2)m.put((String)v[i],v[i+1]);return m;}
 static <T>T safe(Supplier<T> work){try{return work.get();}catch(ApiException e){throw e;}catch(RuntimeException e){throw bad();}}
 static ApiException error(String c){return new ApiException(c,"Credential operation cannot be applied");}
 static ApiException bad(){return error(CommonApiCodes.DEPENDENCY_UNAVAILABLE);}
 static ApiException invalid(){return error(CommonApiCodes.INVALID_ARGUMENT);}
}
