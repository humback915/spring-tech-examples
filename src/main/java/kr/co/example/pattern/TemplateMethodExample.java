package kr.co.example.pattern;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ========================================================================
 * [9-B] Template Method Pattern (템플릿 메서드 패턴)
 * ========================================================================
 *
 * ── 개념 ──
 *
 * 알고리즘의 골격(템플릿)을 상위 클래스에서 정의하고,
 * 구체적인 단계는 하위 클래스에서 구현.
 *
 * "변하지 않는 흐름"은 상위 클래스에서 관리,
 * "변하는 부분"만 하위 클래스에서 오버라이드.
 *
 * ── 구조 ──
 * ┌────────────────────────────────────────┐
 * │ AbstractExportService (추상 클래스)     │
 * │   template method: export()            │
 * │     1. validate() - 공통                │
 * │     2. fetchData() - 추상 (하위 구현)   │
 * │     3. transform() - 추상 (하위 구현)   │
 * │     4. write() - 추상 (하위 구현)       │
 * │     5. cleanup() - 공통 (hook)          │
 * │                                         │
 * │ CsvExportService extends Abstract       │
 * │   → fetchData, transform, write 구현    │
 * │                                         │
 * │ ExcelExportService extends Abstract     │
 * │   → fetchData, transform, write 구현    │
 * └────────────────────────────────────────┘
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 코드 중복 제거 (공통 로직은 상위 클래스)
 * - 알고리즘 구조 변경 시 상위 클래스만 수정
 * - 하위 클래스는 특화된 부분만 구현
 *
 * 주의점:
 * - 상속을 사용하므로 클래스 계층이 깊어질 수 있음
 * - 단계가 많으면 하위 클래스 구현 부담 증가
 * - Java에서는 Strategy 패턴(조합)이 더 유연할 수 있음
 */

// ── 추상 클래스: 알고리즘 골격 정의 ──
@Slf4j
abstract class AbstractExportService {

    /**
     * Template Method - 알고리즘의 골격
     *
     * final로 선언하여 하위 클래스가 흐름을 변경하지 못하게 함.
     * 각 단계는 추상 메서드 또는 hook 메서드로 구성.
     */
    public final String export(String criteria) {
        log.info("[Template] 내보내기 시작 - criteria={}", criteria);

        // 1. 공통: 입력 검증
        validate(criteria);

        // 2. 추상: 데이터 조회 (하위 클래스 구현)
        Object data = fetchData(criteria);

        // 3. 추상: 데이터 변환 (하위 클래스 구현)
        String transformed = transform(data);

        // 4. 추상: 파일 작성 (하위 클래스 구현)
        String filePath = write(transformed);

        // 5. Hook: 정리 작업 (하위 클래스가 선택적으로 오버라이드)
        cleanup();

        log.info("[Template] 내보내기 완료 - file={}", filePath);
        return filePath;
    }

    /** 공통 검증 로직 */
    private void validate(String criteria) {
        if (criteria == null || criteria.isBlank()) {
            throw new IllegalArgumentException("검색 조건이 비어있습니다");
        }
    }

    /** 추상: 데이터 조회 - 하위 클래스에서 구현 */
    protected abstract Object fetchData(String criteria);

    /** 추상: 데이터 변환 - 하위 클래스에서 구현 */
    protected abstract String transform(Object data);

    /** 추상: 파일 작성 - 하위 클래스에서 구현 */
    protected abstract String write(String content);

    /**
     * Hook Method - 선택적 오버라이드
     *
     * 기본 구현이 있지만 하위 클래스가 필요하면 오버라이드 가능.
     * "할 수도 있고 안 할 수도 있는" 단계에 사용.
     */
    protected void cleanup() {
        log.debug("[Template] 기본 cleanup (no-op)");
    }
}

// ── 구현체 1: CSV 내보내기 ──
@Slf4j
@Component
class CsvExportService extends AbstractExportService {

    @Override
    protected Object fetchData(String criteria) {
        log.info("[CSV] DB에서 데이터 조회 - criteria={}", criteria);
        return "raw-data-for-csv";
    }

    @Override
    protected String transform(Object data) {
        log.info("[CSV] CSV 형식으로 변환");
        return "col1,col2,col3\nval1,val2,val3";
    }

    @Override
    protected String write(String content) {
        String path = "/tmp/export.csv";
        log.info("[CSV] 파일 작성 - path={}", path);
        return path;
    }
}

// ── 구현체 2: Excel 내보내기 ──
@Slf4j
@Component
public class TemplateMethodExample extends AbstractExportService {

    @Override
    protected Object fetchData(String criteria) {
        log.info("[Excel] DB에서 데이터 조회 - criteria={}", criteria);
        return "raw-data-for-excel";
    }

    @Override
    protected String transform(Object data) {
        log.info("[Excel] Excel 시트 데이터 변환");
        return "excel-binary-content";
    }

    @Override
    protected String write(String content) {
        String path = "/tmp/export.xlsx";
        log.info("[Excel] 파일 작성 - path={}", path);
        return path;
    }

    /** Hook 오버라이드: Excel은 임시 파일 정리 필요 */
    @Override
    protected void cleanup() {
        log.info("[Excel] 임시 파일 정리");
    }
}
