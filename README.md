# Document Converter API

HWP/HWPX와 Markdown 간의 양방향 변환을 제공하는 REST API 서비스입니다.

[hwp-md-converter](https://github.com/zbum/hwp-md-converter) 라이브러리를 기반으로 합니다.

## 요구사항

- Java 21 이상 (Java 25 권장)
- Maven 3.6 이상
- Docker (컨테이너 실행 시)
- Kubernetes (k8s 배포 시)

## API 엔드포인트

| 엔드포인트 | 메서드 | Content-Type | 설명 |
|-----------|--------|--------------|------|
| `/api/convert/hwp-to-markdown` | POST | multipart/form-data | HWP/HWPX 파일을 Markdown으로 변환 |
| `/api/convert/markdown-to-hwp` | POST | text/plain | Markdown을 HWP 파일로 변환 |
| `/api/convert/markdown-to-hwpx` | POST | text/plain | Markdown을 HWPX 파일로 변환 |
| `/api/convert/hwp-to-hwpx` | POST | multipart/form-data | HWP를 HWPX로 변환 |
| `/api/convert/hwpx-to-hwp` | POST | multipart/form-data | HWPX를 HWP로 변환 |

## 로컬 실행

### sdkman 사용 시

```bash
sdk env
./mvnw spring-boot:run
```

### 직접 실행

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.1-tem
./mvnw spring-boot:run
```

## API 사용 예시

### Markdown → HWP 변환

```bash
curl -X POST http://localhost:8080/api/convert/markdown-to-hwp \
  -H "Content-Type: text/plain" \
  -d "# 제목

본문 내용입니다.

## 소제목

- 항목 1
- 항목 2" \
  --output document.hwp
```

Markdown → HWP 변환은 다음 Markdown 서식을 HWP 문서 객체로 보정합니다.

- `#`, `##`, `###` 제목은 서로 다른 HWP 글자 스타일로 적용
- `*` 글머리 목록은 HWP 글머리표로 변환
- fenced code block은 1x1 표로 변환하고 긴 줄은 폭에 맞춰 줄바꿈
- Markdown 이미지(`![alt](url 또는 file URI)`)는 다운로드 또는 로컬 파일 읽기 후 HWP 그림으로 삽입

### HWP → Markdown 변환

```bash
curl -X POST http://localhost:8080/api/convert/hwp-to-markdown \
  -F "file=@document.hwp"
```

### Markdown → HWPX 변환

```bash
curl -X POST http://localhost:8080/api/convert/markdown-to-hwpx \
  -H "Content-Type: text/plain" \
  -d "# Hello World" \
  --output document.hwpx
```

### HWP ↔ HWPX 변환

```bash
# HWP → HWPX
curl -X POST http://localhost:8080/api/convert/hwp-to-hwpx \
  -F "file=@document.hwp" \
  --output document.hwpx

# HWPX → HWP
curl -X POST http://localhost:8080/api/convert/hwpx-to-hwp \
  -F "file=@document.hwpx" \
  --output document.hwp
```

## Docker 실행

### Docker Compose 사용

```bash
# 빌드 및 실행
docker compose up -d

# 로그 확인
docker compose logs -f

# 중지
docker compose down
```

### Docker 직접 사용

```bash
# 이미지 빌드
docker build -t documentconverter-api:latest .

# 컨테이너 실행
docker run -d \
  --name documentconverter-api \
  -p 8080:8080 \
  documentconverter-api:latest
```

## Kubernetes 배포

### 전체 리소스 배포

```bash
# 네임스페이스 생성
kubectl apply -f k8s/namespace.yaml

# 모든 리소스 배포
kubectl apply -f k8s/

# 또는 개별 배포
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/ingress.yaml
kubectl apply -f k8s/hpa.yaml
```

### 배포 상태 확인

```bash
# Pod 상태 확인
kubectl get pods -n documentconverter

# 서비스 확인
kubectl get svc -n documentconverter

# 로그 확인
kubectl logs -f deployment/documentconverter-api -n documentconverter
```

### 로컬 테스트 (포트 포워딩)

```bash
kubectl port-forward svc/documentconverter-api 8080:80 -n documentconverter
```

### Ingress 경로

- `/api/convert/*` - 변환 API

> `/actuator` 엔드포인트는 외부에 노출되지 않으며, 클러스터 내부에서만 접근 가능합니다.

### 리소스 삭제

```bash
kubectl delete -f k8s/
```

## Jenkins CI/CD

### 사전 요구사항

1. **Jenkins 플러그인**
   - Docker Pipeline
   - Kubernetes CLI
   - Pipeline

2. **Jenkins Credentials**
   - `docker-registry-credentials`: Docker Registry 로그인 (usernamePassword)
   - `kubeconfig`: Kubernetes 설정 파일 (file)

3. **Jenkins Tools**
   - JDK: `JDK25`

### 파이프라인 단계

| 단계 | 설명 |
|-----|------|
| Checkout | 소스 코드 체크아웃 |
| Test | Maven 빌드 및 테스트 |
| Docker Build | Docker 이미지 빌드 및 태깅 |
| Docker Push | registry.manty.co.kr에 이미지 푸시 |
| Deploy to Kubernetes | K8s 리소스 적용 및 롤아웃 |

## 프로젝트 구조

```
documentconverter-api/
├── src/
│   └── main/
│       ├── java/kr/co/manty/documentconverterapi/
│       │   ├── DocumentconverterApiApplication.java
│       │   ├── controller/
│       │   │   ├── DocumentConverterController.java
│       │   │   └── GlobalExceptionHandler.java
│       │   └── service/
│       │       ├── DocumentConverterService.java
│       │       ├── MarkdownDocumentPreProcessor.java
│       │       └── HwpDocumentPostProcessor.java
│       └── resources/
│           └── application.properties
├── k8s/
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── ingress.yaml
│   └── hpa.yaml
├── Dockerfile
├── docker-compose.yml
├── .dockerignore
├── .sdkmanrc
└── pom.xml
```

## 헬스체크 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `/actuator/health` | 전체 헬스 상태 |
| `/actuator/health/liveness` | Liveness probe |
| `/actuator/health/readiness` | Readiness probe |

## 설정

### 환경 변수

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `SERVER_PORT` | 8080 | 서버 포트 |
| `SPRING_PROFILES_ACTIVE` | default | Spring 프로파일 |
| `JAVA_OPTS` | - | JVM 옵션 |

### 파일 업로드 제한

- 최대 파일 크기: 50MB
- 최대 요청 크기: 50MB

## 라이선스

MIT License
