package kr.co.example.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.co.example.exception.CustomException;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * REST API 응답 패턴 예제 — ResponseEntity, 페이징, 표준 응답 래퍼.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  RESTful API HTTP 메서드 컨벤션                                      │
 * ├──────────┬──────────────────────────────────────────────────────────┤
 * │  GET     │ 조회 — 200 OK (데이터 반환)                               │
 * │  POST    │ 생성 — 201 Created (Location 헤더 + 생성된 리소스)         │
 * │  PUT     │ 전체 수정 — 200 OK                                        │
 * │  PATCH   │ 부분 수정 — 200 OK                                        │
 * │  DELETE  │ 삭제 — 204 No Content (본문 없음)                          │
 * └──────────┴──────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  ResponseEntity vs @ResponseStatus vs @ResponseBody                 │
 * ├──────────────────┬──────────────────────────────────────────────────┤
 * │  ResponseEntity  │ 상태코드 + 헤더 + 본문 모두 제어 가능              │
 * │                  │ 동적으로 상태코드 변경 가능 (권장)                  │
 * ├──────────────────┼──────────────────────────────────────────────────┤
 * │  @ResponseStatus │ 메서드/예외 클래스에 고정 상태코드 지정              │
 * │                  │ 간단한 경우에 적합                                  │
 * ├──────────────────┼──────────────────────────────────────────────────┤
 * │  @ResponseBody   │ 반환값을 JSON으로 직렬화                           │
 * │                  │ @RestController에는 이미 포함되어 있음              │
 * └──────────────────┴──────────────────────────────────────────────────┘
 * </pre>
 */
@RestController
@RequestMapping("/api/examples")
public class RestResponseExample {

    // ====================================================================
    // [1] ResponseEntity 기본 사용법
    // ====================================================================

    /**
     * 200 OK — 단건 조회.
     * ResponseEntity.ok(body) — 가장 기본적인 성공 응답.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserDto>> getById(@PathVariable Long id) {
        // 서비스 호출 (예시)
        UserDto user = UserDto.builder()
                .id(id).name("홍길동").email("hong@example.com").build();

        return ResponseEntity.ok(ApiResponse.ok(user));
    }

    /**
     * 201 Created — 리소스 생성.
     * Location 헤더에 생성된 리소스의 URI를 포함 (REST 표준).
     */
    @PostMapping
    public ResponseEntity<ApiResponse<UserDto>> create(@RequestBody UserDto request) {
        // 서비스에서 저장 후 ID 할당 (예시)
        UserDto saved = UserDto.builder()
                .id(1L).name(request.getName()).email(request.getEmail()).build();

        // Location 헤더: /api/examples/1
        URI location = URI.create("/api/examples/" + saved.getId());

        return ResponseEntity
                .created(location)  // 201 Created + Location 헤더
                .body(ApiResponse.ok(saved));
    }

    /**
     * 204 No Content — 삭제 성공 (응답 본문 없음).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        // 서비스에서 삭제 처리
        return ResponseEntity.noContent().build(); // 204 + 빈 본문
    }

    /**
     * 커스텀 헤더 포함 응답.
     * ETag, Cache-Control 등 커스텀 헤더가 필요한 경우.
     */
    @GetMapping("/{id}/with-headers")
    public ResponseEntity<ApiResponse<UserDto>> getWithHeaders(@PathVariable Long id) {
        UserDto user = UserDto.builder()
                .id(id).name("홍길동").email("hong@example.com").build();

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Custom-Header", "custom-value");
        headers.setCacheControl("max-age=3600"); // 1시간 캐시

        return ResponseEntity.ok().headers(headers).body(ApiResponse.ok(user));
    }

    /**
     * @ResponseStatus — 메서드에 고정 상태코드 지정.
     * ResponseEntity 없이 간단하게 사용 가능.
     * 단, 동적으로 상태코드를 변경할 수 없음.
     */
    @PostMapping("/simple")
    @ResponseStatus(HttpStatus.CREATED) // 반환값에 관계없이 항상 201
    public UserDto createSimple(@RequestBody UserDto request) {
        return UserDto.builder()
                .id(1L).name(request.getName()).email(request.getEmail()).build();
    }

    // ====================================================================
    // [2] 페이징 (Pagination) — Pageable, Page, Slice
    // ====================================================================

    /**
     * 페이징 조회 — Pageable 파라미터 자동 바인딩.
     *
     * <pre>
     * 요청 URL 예시:
     * GET /api/examples?page=0&size=10&sort=createdDate,desc
     *
     * Pageable 파라미터 자동 매핑:
     * - page: 페이지 번호 (0부터 시작)
     * - size: 페이지 크기 (기본 20)
     * - sort: 정렬 기준 (컬럼명,방향)
     *
     * Page&lt;T&gt; 응답에 포함되는 메타 정보:
     * - content: 데이터 목록
     * - totalElements: 전체 데이터 수
     * - totalPages: 전체 페이지 수
     * - number: 현재 페이지 번호
     * - size: 페이지 크기
     * - first/last: 첫/마지막 페이지 여부
     * - hasNext/hasPrevious: 다음/이전 페이지 존재 여부
     * </pre>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<UserDto>>> getList(
            // @PageableDefault로 기본값 지정 가능
            // @PageableDefault(size = 10, sort = "createdDate", direction = Sort.Direction.DESC)
            Pageable pageable) {

        // 코드에서 Pageable 생성 예시 (서비스 내부에서 사용 시)
        Pageable customPageable = PageRequest.of(
                0,                        // page: 0번째 페이지
                10,                       // size: 10개
                Sort.by(Sort.Direction.DESC, "createdDate") // 정렬 기준
        );

        // 여러 컬럼 정렬
        Pageable multiSort = PageRequest.of(0, 10,
                Sort.by(
                        Sort.Order.desc("createdDate"),
                        Sort.Order.asc("name")
                ));

        // 서비스 호출 → Page<T> 반환 (예시)
        // Page<UserDto> result = userService.findAll(pageable);
        return ResponseEntity.ok(ApiResponse.ok(Page.empty(pageable)));
    }

    // ====================================================================
    // [3] 페이징 방식 비교 — 오프셋 기반 vs 커서 기반
    // ====================================================================

    /**
     * 페이징 방식 2가지 — Offset-based vs Cursor-based(Keyset).
     *
     * <pre>
     * ┌──────────────────────────────────────────────────────────────────────┐
     * │  Offset-based (오프셋 기반)                                           │
     * ├──────────────────────────────────────────────────────────────────────┤
     * │  SQL: SELECT * FROM orders ORDER BY id DESC LIMIT 10 OFFSET 100     │
     * │                                                                      │
     * │  ✓ 장점: 특정 페이지로 바로 이동 가능 (1페이지 → 5페이지)              │
     * │  ✓ 장점: Spring Data의 Page/Pageable 기본 지원                       │
     * │  ✗ 단점: OFFSET이 클수록 느림 (앞의 데이터를 모두 스캔 후 건너뜀)      │
     * │         OFFSET 100000 → 100000건 스캔 후 10건 반환                   │
     * │  ✗ 단점: 실시간 데이터 추가/삭제 시 중복·누락 발생 가능                │
     * │                                                                      │
     * │  적합: 관리자 페이지, 전체 페이지 수 표시가 필요한 UI                   │
     * └──────────────────────────────────────────────────────────────────────┘
     *
     * ┌──────────────────────────────────────────────────────────────────────┐
     * │  Cursor-based (커서 기반 / Keyset Pagination)                         │
     * ├──────────────────────────────────────────────────────────────────────┤
     * │  SQL: SELECT * FROM orders WHERE id &lt; :lastId                   │
     * │       ORDER BY id DESC LIMIT 10                                     │
     * │                                                                      │
     * │  ✓ 장점: 데이터 양에 관계없이 일정한 성능 (인덱스 범위 스캔)           │
     * │  ✓ 장점: 실시간 데이터 추가/삭제에도 중복·누락 없음                    │
     * │  ✗ 단점: 특정 페이지로 바로 이동 불가 (순차 탐색만 가능)               │
     * │  ✗ 단점: 정렬 기준 컬럼에 인덱스 필수                                 │
     * │                                                                      │
     * │  적합: 무한 스크롤, "더보기" 버튼, 모바일 앱, 대용량 데이터             │
     * └──────────────────────────────────────────────────────────────────────┘
     *
     * ┌──────────────────────────────────────────────────────────────────────┐
     * │  비교 요약                                                            │
     * ├──────────────────┬────────────────────┬──────────────────────────────┤
     * │                  │  오프셋 기반        │  커서 기반                    │
     * ├──────────────────┼────────────────────┼──────────────────────────────┤
     * │  SQL             │  OFFSET + LIMIT    │  WHERE id &lt; ? + LIMIT    │
     * │  성능            │  뒤쪽 페이지 느림  │  항상 일정                    │
     * │  페이지 이동     │  자유롭게 이동     │  순차 이동만 가능              │
     * │  데이터 정합성   │  중복/누락 가능    │  안전                         │
     * │  전체 개수       │  COUNT 쿼리 가능   │  전체 개수 알기 어려움         │
     * │  Spring 지원     │  Page, Pageable    │  직접 구현 필요               │
     * │  UI 패턴         │  페이지 번호 탐색  │  무한 스크롤 / 더보기          │
     * └──────────────────┴────────────────────┴──────────────────────────────┘
     * </pre>
     */

    /**
     * 커서 기반 페이징 — 컨트롤러 예시.
     *
     * <pre>
     * 요청 예시:
     * GET /api/examples/cursor?size=10                    (첫 페이지)
     * GET /api/examples/cursor?cursor=152&size=10         (다음 페이지: ID 152 이후)
     * </pre>
     */
    @GetMapping("/cursor")
    public ResponseEntity<ApiResponse<CursorPageResponse<UserDto>>> getListByCursor(
            @RequestParam(required = false) Long cursor, // 마지막 조회 항목의 ID (첫 페이지는 null)
            @RequestParam(defaultValue = "10") int size) {

        // ── 서비스 계층 구현 예시 ──
        //
        // [Repository]
        // @Query("SELECT u FROM User u WHERE (:cursor IS NULL OR u.id < :cursor) " +
        //        "ORDER BY u.id DESC")
        // List<User> findByCursor(@Param("cursor") Long cursor, Pageable pageable);
        //
        // [Service]
        // Pageable pageable = PageRequest.of(0, size + 1); // 1건 더 조회 (hasNext 판단)
        // List<User> users = userRepository.findByCursor(cursor, pageable);
        //
        // boolean hasNext = users.size() > size;
        // if (hasNext) users = users.subList(0, size); // 초과분 제거
        //
        // Long nextCursor = hasNext ? users.get(users.size() - 1).getId() : null;
        // return new CursorPageResponse<>(users, nextCursor, hasNext);

        return ResponseEntity.ok(ApiResponse.ok(
                CursorPageResponse.empty()));
    }

    /**
     * 커서 기반 페이징 응답 DTO.
     *
     * <pre>
     * 응답 JSON:
     * {
     *   "data": {
     *     "content": [ ... ],       ← 데이터 목록
     *     "nextCursor": 142,        ← 다음 요청 시 전달할 커서 값
     *     "hasNext": true           ← 다음 페이지 존재 여부
     *   }
     * }
     *
     * QueryDSL 커서 기반 구현:
     *
     * public CursorPageResponse&lt;OrderDto&gt; searchByCursor(Long cursor, int size) {
     *     List&lt;Order&gt; orders = queryFactory
     *         .selectFrom(order)
     *         .where(
     *             cursorLt(cursor),           // cursor가 null이면 조건 무시 (첫 페이지)
     *             statusEq(OrderStatus.PAID)
     *         )
     *         .orderBy(order.id.desc())
     *         .limit(size + 1)               // 1건 더 조회
     *         .fetch();
     *
     *     boolean hasNext = orders.size() &gt; size;
     *     if (hasNext) orders = orders.subList(0, size);
     *
     *     Long nextCursor = hasNext ? orders.get(orders.size() - 1).getId() : null;
     *     return new CursorPageResponse&lt;&gt;(toDto(orders), nextCursor, hasNext);
     * }
     *
     * private BooleanExpression cursorLt(Long cursor) {
     *     return cursor != null ? order.id.lt(cursor) : null;
     * }
     * </pre>
     */
    @Getter
    @Builder
    public static class CursorPageResponse<T> {
        private final List<T> content;     // 데이터 목록
        private final Long nextCursor;     // 다음 페이지 커서 (null이면 마지막 페이지)
        private final boolean hasNext;     // 다음 페이지 존재 여부

        public static <T> CursorPageResponse<T> empty() {
            return CursorPageResponse.<T>builder()
                    .content(List.of())
                    .nextCursor(null)
                    .hasNext(false)
                    .build();
        }
    }

    // ====================================================================
    // [4] ApiResponse — 표준 응답 래퍼 (concert-msa-project 패턴)
    // ====================================================================

    /**
     * 표준 API 응답 래퍼.
     *
     * <pre>
     * 성공 응답:
     * { "data": { ... } }
     *
     * 실패 응답:
     * { "error": { "errorCode": "USER_NOT_FOUND", "errorMessage": "사용자를 찾을 수 없습니다" } }
     *
     * @JsonInclude(NON_NULL): null 필드는 JSON에서 제외
     * → 성공 시 error 필드 없음, 실패 시 data 필드 없음
     * </pre>
     */
    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiResponse<T> {

        private final T data;
        private final Error error;

        /** 에러 상세 — record로 간결하게 정의 */
        public record Error(String errorCode, String errorMessage) {
        }

        // ── 정적 팩토리 메서드 ──

        /** 성공 (데이터 없음) */
        public static <T> ApiResponse<T> ok() {
            return ApiResponse.<T>builder().build();
        }

        /** 성공 (데이터 포함) */
        public static <T> ApiResponse<T> ok(T data) {
            return ApiResponse.<T>builder().data(data).build();
        }

        /** 실패 */
        public static <T> ApiResponse<T> fail(String errorCode, String errorMessage) {
            return ApiResponse.<T>builder()
                    .error(new Error(errorCode, errorMessage))
                    .build();
        }
    }

    // ====================================================================
    // [4] DTO (Data Transfer Object)
    // ====================================================================

    @Getter
    @Builder
    public static class UserDto {
        private Long id;
        private String name;
        private String email;
    }
}
