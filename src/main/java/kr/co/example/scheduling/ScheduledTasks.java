package kr.co.example.scheduling;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ========================================================================
 * @Scheduled 사용 예제 - 다양한 스케줄링 방식
 * ========================================================================
 *
 * ── fixedRate vs fixedDelay ──
 *
 * fixedRate = 3000 (작업 소요 2초):
 *   |--작업(2s)--|--대기(1s)--|--작업(2s)--|
 *   0s           3s           6s
 *   → 시작 시점 기준 3초 간격
 *
 * fixedDelay = 3000 (작업 소요 2초):
 *   |--작업(2s)--|----대기(3s)----|--작업(2s)--|
 *   0s            2s              5s
 *   → 완료 시점 기준 3초 후 다음 실행
 *
 * ── 권장 사용 시나리오 ──
 *
 * fixedRate: 주기적 폴링, 모니터링 (실행 간격이 중요)
 * fixedDelay: 외부 API 호출, 배치 처리 (이전 작업 완료 후 실행이 중요)
 * cron: 특정 시간대 작업 (매일 새벽, 매주 월요일 등)
 */
@Slf4j
@Component
public class ScheduledTasks {

    /**
     * fixedRate 예제 - 3초 간격 실행
     *
     * 이전 작업의 시작 시점 기준으로 다음 실행 시간 계산.
     * 작업이 3초보다 오래 걸리면 완료 즉시 다음 실행.
     *
     * initialDelay = 1000: 앱 시작 후 1초 뒤 첫 실행
     * → 초기화가 완료된 후 작업 시작하기 위해 사용
     */
    @Scheduled(fixedRate = 3000, initialDelay = 1000)
    public void pollQueueTask() {
        log.info("[fixedRate] 큐 폴링 실행 - thread={}", Thread.currentThread().getName());
        // 예: Redis 큐에서 대기 중인 작업을 가져와 처리
    }

    /**
     * fixedDelay 예제 - 이전 작업 완료 후 10초 뒤 실행
     *
     * 이전 작업의 완료 시점 기준으로 대기 시간 적용.
     * 작업이 얼마나 걸리든 완료 후 정확히 10초 대기.
     *
     * 적합한 시나리오:
     * - 외부 API 호출 후 처리 (중복 호출 방지)
     * - DB 배치 처리 (이전 배치 완료 확인 후 실행)
     */
    @Scheduled(fixedDelay = 10000)
    public void batchProcessTask() {
        log.info("[fixedDelay] 배치 처리 실행 - thread={}", Thread.currentThread().getName());
        // 예: 만료된 장바구니 정리, 주문 상태 업데이트
    }

    /**
     * cron 예제 - 매 1분마다 실행
     *
     * cron = "0 0/1 * * * ?"
     * → 초(0) 분(매1분) 시(매시) 일(매일) 월(매월) 요일(무관)
     *
     * 자주 쓰는 cron 표현식:
     * - "0 0 2 * * ?"        : 매일 새벽 2시
     * - "0 0 0/6 * * ?"      : 6시간마다
     * - "0 30 9 * * MON-FRI" : 평일 오전 9시 30분
     * - "0 0 0 1 * ?"        : 매월 1일 자정
     */
    @Scheduled(cron = "0 0/1 * * * ?")
    public void minutelyTask() {
        log.info("[cron] 1분 주기 작업 실행 - thread={}", Thread.currentThread().getName());
        // 예: 장바구니 만료 체크, 임시 데이터 정리
    }

    /**
     * cron 예제 - 매일 새벽 2시 실행
     *
     * 일일 배치 작업에 적합.
     * 트래픽이 적은 시간대에 실행하여 서비스 영향 최소화.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyBatchTask() {
        log.info("[cron] 일일 배치 실행 - thread={}", Thread.currentThread().getName());
        // 예: 환불 처리, 정산, 통계 집계
    }

    /**
     * 세션 정리 - 30초 간격
     *
     * 만료된 세션을 주기적으로 정리.
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupExpiredSessions() {
        log.info("[fixedRate] 만료 세션 정리 - thread={}", Thread.currentThread().getName());
        // 예: Redis에서 만료된 대기열 세션 삭제
    }
}
