package com.petplatform.user.biz.apiimpl;

import com.petplatform.common.*;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.PetSnapshotDTO;
import com.petplatform.user.api.query.BookingUserFactsApi;
import com.petplatform.user.biz.infrastructure.persistence.PetStore;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** USER-owned facts for internal booking; the transport must supply a trusted USER context. */
public final class BookingUserFactsApiImpl implements BookingUserFactsApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final JdbcTemplate jdbc;
    private final PetStore pets;
    public BookingUserFactsApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        this.source=Objects.requireNonNull(source); this.guard=Objects.requireNonNull(guard);
        jdbc=new JdbcTemplate(source); pets=new PetStore(source);
    }
    @Override public void checkActor(String userId, QueryContext context) { actor(userId,context,false); }
    @Override public void requireCurrentActor(String userId,String storeId,QueryContext context) {
        guard.requireHeld(storeId,source); actor(userId,context,true);
    }
    private void actor(String userId,QueryContext context,boolean lock) {
        long id=id(userId);
        if(context==null || context.operatorType()!=OperatorType.USER || !userId.equals(context.operatorId()))
            throw new ApiException(CommonApiCodes.FORBIDDEN,"booking requires the authenticated user");
        try {
            var rows=jdbc.queryForList("SELECT status FROM user_account WHERE id=?"+(lock?" FOR UPDATE":""),String.class,id);
            if(rows.isEmpty()) throw new ApiException(CommonApiCodes.UNAUTHORIZED,"user unavailable");
            String status=rows.getFirst();
            if(!Set.of("ACTIVE","FROZEN","CANCELED").contains(status)) throw unavailable();
            if(!"ACTIVE".equals(status)) throw new ApiException(CommonApiCodes.FORBIDDEN,"user unavailable");
        } catch(ApiException known) { if(lock) rollback(); throw known; }
        catch(RuntimeException failed) { if(lock) rollback(); throw unavailable(); }
    }
    @Override public PetSnapshotDTO readCurrentPet(String userId,String petId,String storeId,QueryContext context) {
        requireCurrentActor(userId,storeId,context);
        try {
            var pet=pets.findPet(id(petId),true);
            if(pet==null || pet.userId()!=id(userId) || "DISABLED".equals(pet.status()))
                throw new ApiException("PET_NOT_FOUND","pet unavailable");
            if(!"ACTIVE".equals(pet.status()) || pet.id()<=0 || pet.name()==null || pet.name().isBlank()
                    || !Set.of("DOG","CAT","OTHER").contains(pet.petType())
                    || (pet.sex()!=null && !Set.of("MALE","FEMALE","UNKNOWN").contains(pet.sex()))
                    || (pet.weightKg()!=null && (pet.weightKg().signum()<=0
                        || pet.weightKg().compareTo(new java.math.BigDecimal("999.99"))>0))) throw unavailable();
            return new PetSnapshotDTO(IDS.toApi(pet.id()),IDS.toApi(pet.userId()),pet.name(),pet.petType(),
                    pet.breedName(),pet.sex(),pet.weightKg(),pet.healthNote());
        } catch(ApiException known) { rollback(); throw known; }
        catch(RuntimeException failed) { rollback(); throw unavailable(); }
    }
    private static long id(String text) {
        try{return IDS.fromApi(text);}catch(IllegalArgumentException invalid){
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid user or pet id");}
    }
    private void rollback(){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();}
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"booking user facts unavailable");}
}
