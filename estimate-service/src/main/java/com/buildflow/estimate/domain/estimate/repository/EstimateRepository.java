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

    /** 손익 원천 조회용: 상태와 무관한 현장의 견적 id(이벤트 record_key 대조용). */
    @Query("select e.id from Estimate e where e.siteId = :siteId order by e.id")
    List<Long> findIdsBySiteId(@Param("siteId") Long siteId);

    /** 전체 견적의 id를 읽어 손익 outbox의 고아 record_key를 정확히 판별한다. */
    @Query("select e.id from Estimate e")
    List<Long> findAllIds();

    /** 손익 원천 조회용: 손익에 반영되는 확정 견적(CONFIRMED)만. 초안·대체본(SUPERSEDED) 제외. */
    @Query("select e from Estimate e where e.siteId = :siteId and e.status = :status order by e.id")
    List<Estimate> findBySiteIdAndStatus(@Param("siteId") Long siteId,
                                         @Param("status") EstimateStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Estimate e where e.id = :id")
    Optional<Estimate> findByIdForUpdate(@Param("id") Long id);

    /**
     * 체인 루트 id만 스칼라로 읽는다. chainRootId는 생성 후 불변이라 잠금 전 스냅숏으로 읽어도 되고,
     * 엔티티를 영속성 컨텍스트에 올리지 않아 이후 잠금 조회가 최신 커밋 상태를 읽게 둔다.
     */
    @Query("select coalesce(e.chainRootId, e.id) from Estimate e where e.id = :id")
    Optional<Long> findChainRootId(@Param("id") Long id);

    /**
     * 루트를 가리키는 수정본 중 주어진 상태의 견적을 잠금 조회한다(루트 자신은 호출자가 이미 잠갔다).
     * chain_root_id 등호 조건만 써서 idx_estimates_chain_root 범위만 잠근다 — status·OR 조건이면
     * 전체 스캔으로 다른 체인 행까지 잠가 서로 다른 체인의 동시 확정이 교착될 수 있다(PR #201 리뷰).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Estimate e where e.chainRootId = :rootId and e.status = :status order by e.id")
    List<Estimate> findRevisionsByStatusForUpdate(@Param("rootId") Long rootId,
                                                  @Param("status") EstimateStatus status);
}
