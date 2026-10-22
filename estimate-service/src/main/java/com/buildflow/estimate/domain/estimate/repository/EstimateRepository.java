package com.buildflow.estimate.domain.estimate.repository;

import com.buildflow.estimate.domain.estimate.entity.Estimate;
import com.buildflow.estimate.domain.estimate.entity.EstimateStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EstimateRepository extends JpaRepository<Estimate, Long> {

    List<Estimate> findBySiteIdOrderByCreatedAtDesc(Long siteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Estimate e where e.id = :id")
    Optional<Estimate> findByIdForUpdate(@Param("id") Long id);

    /**
     * 체인 루트 id만 스칼라로 읽는다. chainRootId는 생성 후 불변이라 잠금 전 스냅숏으로 읽어도 되고,
     * 엔티티를 영속성 컨텍스트에 올리지 않아 이후 잠금 조회가 최신 커밋 상태를 읽게 둔다.
     */
    @Query("select coalesce(e.chainRootId, e.id) from Estimate e where e.id = :id")
    Optional<Long> findChainRootId(@Param("id") Long id);

    /** 같은 수정 체인(루트 + 루트를 가리키는 수정본)에서 주어진 상태의 견적을 잠금 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Estimate e where e.status = :status"
            + " and (e.id = :rootId or e.chainRootId = :rootId) order by e.id")
    List<Estimate> findChainMembersByStatusForUpdate(@Param("rootId") Long rootId,
                                                     @Param("status") EstimateStatus status);
}
