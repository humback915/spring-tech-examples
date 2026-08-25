package kr.co.example.scheduling;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * ========================================================================
 * [4] 스케줄링 설정 - @Scheduled, TaskScheduler
 * ========================================================================
 *
 * ── 핵심 개념 ──
 *
 * 1. @EnableScheduling
 *    - Spring의 스케줄링 인프라를 활성화
 *    - @Scheduled 어노테이션 사용 가능하게 함
 *    - 내부적으로 TaskScheduler를 통해 작업 실행
 *
 * 2. @Scheduled 옵션 비교
 *    ┌──────────────┬───────────────────────────────────────────┐
 *    │ fixedRate     │ 이전 시작 시점 기준 N ms 간격 실행          │
 *    │               │ 작업이 N ms보다 오래 걸리면 즉시 다음 실행  │
 *    ├──────────────┼───────────────────────────────────────────┤
 *    │ fixedDelay    │ 이전 완료 시점 기준 N ms 후 실행            │
 *    │               │ 작업 완료를 기다린 후 간격 적용             │
 *    ├──────────────┼───────────────────────────────────────────┤
 *    │ cron          │ Unix cron 표현식으로 실행 시점 지정         │
 *    │               │ 초 분 시 일 월 요일                        │
 *    ├──────────────┼───────────────────────────────────────────┤
 *    │ initialDelay  │ 애플리케이션 시작 후 첫 실행까지 대기 시간  │
 *    └──────────────┴───────────────────────────────────────────┘
 *
 * 3. Cron 표현식
 *    초(0-59) 분(0-59) 시(0-23) 일(1-31) 월(1-12) 요일(0-7)
 *    예: "0 0 2 * * ?"   → 매일 새벽 2시
 *        "0 0/1 * * * ?" → 매 1분마다
 *        "0 30 9 * * MON-FRI" → 평일 오전 9시 30분
 *
 * 4. ThreadPoolTaskScheduler
 *    - @Scheduled 작업을 실행하는 스레드 풀
 *    - 기본값은 단일 스레드 → 동시 실행 불가
 *    - poolSize를 늘려 여러 작업 동시 실행 가능
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 별도 라이브러리 없이 간단한 반복/예약 작업 구현
 * - cron 표현식으로 복잡한 실행 스케줄 지정
 * - Spring 컨텍스트 내에서 동작 → DI, 트랜잭션 등 모든 기능 사용 가능
 *
 * 주의점:
 * - 기본 단일 스레드 → 하나의 작업이 오래 걸리면 다른 작업 지연
 * - 분산 환경에서 중복 실행 방지 필요 (ShedLock, Quartz 등)
 * - 예외 발생 시 해당 스케줄 작업이 중단될 수 있음
 */
@Slf4j
@Configuration
@EnableScheduling  // @Scheduled 어노테이션 활성화
public class ScheduleConfig {

    /**
     * TaskScheduler 커스터마이징
     *
     * 기본 TaskScheduler는 스레드 1개만 사용.
     * 여러 @Scheduled 작업이 있으면 병목이 될 수 있으므로
     * 풀 크기를 적절히 설정.
     *
     * poolSize: 동시 실행 가능한 @Scheduled 작업 수
     * threadNamePrefix: 로그에서 스레드 식별용 접두사
     * waitForTasksToCompleteOnShutdown: 종료 시 실행 중인 작업 완료 대기
     * awaitTerminationSeconds: 완료 대기 최대 시간(초)
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);                                // 동시 실행 스레드 수
        scheduler.setThreadNamePrefix("scheduler-");             // 스레드 이름 접두사
        scheduler.setWaitForTasksToCompleteOnShutdown(true);     // 종료 시 작업 완료 대기
        scheduler.setAwaitTerminationSeconds(30);                // 최대 30초 대기
        scheduler.initialize();

        log.info("[Scheduler] TaskScheduler 초기화 - poolSize=3");
        return scheduler;
    }
}
