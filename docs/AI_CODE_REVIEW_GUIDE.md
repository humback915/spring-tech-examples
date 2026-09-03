# GitHub PR AI 코드 리뷰 자동화 구축 가이드

> PR 생성 시 Google Gemini 무료 API를 호출하여 코드 리뷰 코멘트를 자동으로 작성하는 프로세스

### 관련 소스 코드

| # | 파일 | 설명 |
|---|------|------|
| 1 | [ai-code-review.yml](../.github/workflows/ai-code-review.yml) | GitHub Actions 워크플로우 — diff 추출 → Gemini API → PR 코멘트 자동 게시 |

---

## 전체 동작 흐름

```
개발자가 PR 생성/업데이트
  ↓
GitHub Actions 워크플로우 자동 트리거
  ↓
git diff로 변경된 코드 추출
  ↓
Gemini API에 diff + 리뷰 프롬프트 전송
  ↓
AI 리뷰 응답 수신
  ↓
PR 코멘트로 자동 게시
```

---

## Step 1. Gemini API Key 발급

1. [Google AI Studio](https://aistudio.google.com) 접속
2. Google 계정으로 로그인
3. 좌측 메뉴 **"Get API Key"** 클릭
4. **"Create API Key"** 버튼 클릭
5. 생성된 API Key 복사 (예: `AIzaSy...`)

### 무료 티어 제한

| 항목 | 제한 |
|------|------|
| 모델 | gemini-3.6-flash |
| 분당 요청 | 15회 |
| 일일 요청 | 1,500회 |
| 비용 | 무료 |
| 기간 제한 | 없음 (계속 사용 가능) |

> PR 리뷰 용도로는 일 1,500회면 사실상 무제한이다.

---

## Step 2. GitHub Repository에 Secret 등록

API Key를 코드에 직접 넣지 않고, GitHub Secret에 안전하게 저장한다.

```
GitHub 리포지토리 페이지
  → Settings (탭)
  → 좌측 메뉴: Secrets and variables → Actions
  → "New repository secret" 버튼
  → Name: GEMINI_API_KEY
  → Secret: (Step 1에서 복사한 API Key 붙여넣기)
  → "Add secret" 클릭
```

### Secret이 사용되는 방식

```yaml
# 워크플로우에서 이렇게 참조 (값은 외부에 노출되지 않음)
env:
  GEMINI_API_KEY: ${{ secrets.GEMINI_API_KEY }}
```

- `GITHUB_TOKEN`은 별도 등록 불필요 (GitHub가 자동 제공)
- Secret은 워크플로우 로그에도 마스킹 처리됨

---

## Step 3. 워크플로우 파일 생성

`.github/workflows/ai-code-review.yml` 파일을 생성한다.

### 파일 위치

```
프로젝트 루트/
└── .github/
    └── workflows/
        └── ai-code-review.yml   ← 이 파일
```

### 워크플로우 구조

```yaml
name: AI Code Review (Gemini)

on:
  pull_request:
    types: [opened, synchronize]  # PR 생성 또는 새 커밋 push 시

permissions:
  contents: read        # 코드 읽기
  pull-requests: write  # PR 코멘트 작성
```

### 워크플로우 Step별 동작

```
Step 1: Checkout
  → 코드 체크아웃 (fetch-depth: 0으로 전체 히스토리)

Step 2: Check API Key
  → GEMINI_API_KEY Secret 존재 여부 확인
  → 없으면 이후 Step 스킵

Step 3: Get PR diff
  → git diff로 base 브랜치와의 변경 내용 추출
  → 대상: *.java, *.yml, *.yaml, *.gradle, *.properties, *.md
  → /tmp/pr_diff.txt 파일로 저장

Step 4: Call Gemini API and post review
  → Python 스크립트로 실행 (bash 특수문자 문제 방지)
  → diff 파일 읽기 → Gemini API 호출 → PR 코멘트 게시
```

---

## Step 4. 프롬프트 설계

AI 리뷰의 품질은 프롬프트에 의해 결정된다. 4개 섹션으로 구성했다.

### 4-1. 역할 부여

```
당신은 5년 이상 경력의 시니어 Java/Spring Boot 백엔드 개발자이자 코드 리뷰어입니다.
대규모 트래픽 환경에서의 운영 경험이 있으며, 아래 기술 스택에 정통합니다.
```

### 4-2. 프로젝트 기술 스택 명시

```
- Java 21 (record, sealed, text block, pattern matching 등)
- Spring Boot 3.3.x / Spring Framework 6.x
- Spring Data JPA + QueryDSL 5.1
- Spring Data Redis (Lettuce) + Redisson
- Spring Kafka
- Spring Security + JWT (JJWT 0.12.x)
- MapStruct 1.6.x
- Lombok (@Slf4j, @RequiredArgsConstructor, @Builder 등)
- Gradle (Groovy DSL)
- springdoc-openapi 2.6 (Swagger)
```

> 기술 스택을 명시하면 AI가 해당 버전에 맞는 리뷰를 작성한다.
> 예: Spring Boot 3.x에서 deprecated된 API를 잡아내거나, Java 21 문법 활용을 제안.

### 4-3. 코딩 컨벤션

```
1. 모든 주석은 한글로 작성
2. 클래스 상단에 ASCII 다이어그램으로 동작 흐름 설명
3. 각 섹션은 // ======== 구분선 사용
4. Lombok 활용: @Slf4j, @RequiredArgsConstructor, @Builder
5. 필드 주입(@Autowired) 대신 생성자 주입 사용
6. 매직 넘버 대신 static final 상수 정의
7. DTO/VO에 Java record 사용 권장
8. Optional은 반환 타입에만 사용
9. 예외 처리: 커스텀 예외 + @RestControllerAdvice
10. 로깅: @Slf4j + {} 플레이스홀더
```

> 프로젝트의 규칙을 알려줘야 규칙에 맞는 리뷰를 한다.
> 예: 필드 주입 코드가 있으면 "생성자 주입으로 변경하세요"라고 제안.

### 4-4. 리뷰 중점 사항 (우선순위 순서)

```
1. 동시성/스레드 안전성    ← 최우선
2. 리소스 누수
3. 예외 처리
4. 보안
5. 성능
6. Spring 안티패턴
7. API 설계

리뷰 제외:
- import 순서, 공백 스타일
- 테스트 코드 부재
- JavaDoc 누락
```

> 제외 항목을 명시하지 않으면 "테스트를 추가하세요" 같은 불필요한 리뷰가 달린다.

---

## Step 5. PR 생성 및 실행 확인

### 브랜치 생성 → 커밋 → Push → PR 생성

```bash
# feature 브랜치 생성
git checkout -b feature/my-feature

# 코드 변경 후 커밋
git add .
git commit -m "feat: 새 기능 추가"

# push
git push -u origin feature/my-feature

# PR 생성 (GitHub CLI 사용 시)
gh pr create --title "새 기능 추가" --body "설명"
```

### 실행 확인

```
GitHub 리포지토리
  → Actions 탭
  → "AI Code Review (Gemini)" 워크플로우 클릭
  → 실행 로그 확인
  → 완료 후 PR 페이지에서 코멘트 확인
```

---

## 트러블슈팅

### 구축 과정에서 발생한 문제와 해결

| # | 문제 | 원인 | 해결 |
|---|------|------|------|
| 1 | `Unrecognized named-value: 'secrets'` | `secrets`는 job 레벨 `if`에서 사용 불가 | 별도 step에서 `env`로 주입 후 확인 |
| 2 | `syntax error near unexpected token '('` | `${{ outputs.diff }}`가 bash에 직접 삽입되어 diff 내 괄호가 bash 구문 오류 유발 | diff를 파일로 저장 후 Python에서 읽는 방식으로 변경 |
| 3 | `Gemini API 404 Not Found` | `gemini-2.0-flash` 모델 서비스 종료 | `gemini-3.6-flash`로 모델 변경 |
| 4 | `Node.js 20 deprecated` 경고 | `actions/checkout@v4`가 Node.js 20 기반 | `actions/checkout@v5`로 업그레이드 |

### 핵심 교훈

**bash에서 동적 콘텐츠를 다룰 때 `${{ }}`로 직접 삽입하면 안 된다.**

```yaml
# 위험: diff에 괄호, 백틱 등이 있으면 bash 구문 오류
run: |
  echo "${{ steps.diff.outputs.diff }}" | python3 ...

# 안전: 파일로 저장 후 Python에서 읽기
run: |
  echo "$DIFF" > /tmp/pr_diff.txt
  python3 << 'SCRIPT'
  with open("/tmp/pr_diff.txt") as f:
      diff = f.read()
  SCRIPT
```

---

## 파일 구성

```
.github/
└── workflows/
    └── ai-code-review.yml
        │
        ├── Step 1: actions/checkout@v5
        │     코드 체크아웃
        │
        ├── Step 2: Check API key
        │     GEMINI_API_KEY 존재 확인
        │
        ├── Step 3: Get PR diff
        │     git diff → /tmp/pr_diff.txt
        │
        └── Step 4: Python script
              ├── diff 파일 읽기 (12,000자 제한)
              ├── 프롬프트 구성 (역할 + 스택 + 컨벤션 + 중점사항)
              ├── Gemini API 호출 (urllib)
              └── PR 코멘트 게시 (GitHub API)
```

---

## 무료 AI API 비교 (2026년 8월 기준)

| 모델 | 무료 티어 | 일일 한도 | 상태 |
|------|----------|----------|------|
| **Gemini 3.6 Flash** | **무료** | **1,500회/일** | **현재 사용 중** |
| Gemini 3.7 Flash | 무료 | 1,500회/일 | Preview (안정성 미검증) |
| Qwen | 종료 | - | 2026.04 무료 티어 종료 |
| OpenAI GPT | 없음 | - | 유료만 |
| Claude API | 없음 | - | 유료만 |
| Ollama (로컬) | 무료 | 무제한 | GitHub Actions Runner에 GPU 없어 부적합 |

---

## 한계 및 주의사항

- AI 리뷰는 **참고용**이며, 사람 리뷰를 대체할 수 없다
- 무료 모델의 성능 한계로 복잡한 비즈니스 로직 리뷰는 부정확할 수 있다
- diff가 12,000자를 초과하면 잘려서 전송되므로 대규모 변경에는 불완전한 리뷰가 될 수 있다
- 민감한 코드(시크릿, 내부 로직)가 외부 API로 전송되므로 보안 정책 확인 필요
