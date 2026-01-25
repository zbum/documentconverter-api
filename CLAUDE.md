# CLAUDE.md

이 파일은 Claude Code가 이 프로젝트에서 작업할 때 참조하는 규칙과 컨텍스트를 정의합니다.

## 프로젝트 개요

HWP/HWPX와 Markdown 간의 양방향 변환을 제공하는 REST API 서비스입니다.

## 기술 스택

- Java 25 (Eclipse Temurin)
- Spring Boot 4.x
- Maven
- Docker
- Kubernetes

## Jenkinsfile 작성 규칙

Jenkinsfile 생성 시 다음 형식을 따릅니다:

```groovy
pipeline {
    agent any

    tools {
        // 언어별 tool 설정
        // Java: jdk 'JDK25'
        // Go: go 'go-1.25'
    }

    environment {
        DOCKER_REGISTRY = 'registry.manty.co.kr'
        IMAGE_NAME = '프로젝트명'
        IMAGE_TAG = "${env.BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Test') {
            steps {
                // 언어별 테스트 명령
                // Java: sh './mvnw clean verify -B'
                // Go: sh 'make test'
            }
        }

        stage('Docker Build') {
            steps {
                sh "docker build -t ${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} ."
                sh "docker tag ${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} ${DOCKER_REGISTRY}/${IMAGE_NAME}:latest"
            }
        }

        stage('Docker Push') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'docker-registry-credentials',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS'
                )]) {
                    sh "echo ${DOCKER_PASS} | docker login ${DOCKER_REGISTRY} -u ${DOCKER_USER} --password-stdin"
                    sh "docker push ${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"
                    sh "docker push ${DOCKER_REGISTRY}/${IMAGE_NAME}:latest"
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                    sh """
                        # 이미지 태그를 빌드 번호로 업데이트
                        sed -i 's|image: ${DOCKER_REGISTRY}/${IMAGE_NAME}:.*|image: ${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}|' k8s/deployment.yaml

                        # Kubernetes 리소스 적용 (순서 중요!)
                        kubectl apply -f k8s/namespace.yaml
                        kubectl apply -f k8s/configmap.yaml
                        kubectl apply -f k8s/deployment.yaml
                        kubectl apply -f k8s/service.yaml
                        kubectl apply -f k8s/ingress.yaml

                        # 배포 완료 대기
                        kubectl rollout status deployment/${IMAGE_NAME} -n 네임스페이스 --timeout=300s
                    """
                }
            }
        }
    }

    post {
        always {
            sh "docker rmi ${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} || true"
            sh "docker rmi ${DOCKER_REGISTRY}/${IMAGE_NAME}:latest || true"
        }
    }
}
```

### Jenkins Credentials

- `docker-registry-credentials`: Docker Registry 로그인 (usernamePassword)
- `kubeconfig`: Kubernetes 설정 파일 (file)

### Docker Registry

- 기본 레지스트리: `registry.manty.co.kr`

## Kubernetes 배포 규칙

### Jenkinsfile과 K8s 이미지 일관성

**중요**: Jenkinsfile의 `DOCKER_REGISTRY`와 K8s `deployment.yaml`의 이미지 경로는 반드시 일치해야 합니다.

| 파일 | 설정 |
|-----|------|
| Jenkinsfile | `DOCKER_REGISTRY = 'registry.manty.co.kr'` |
| k8s/deployment.yaml | `image: registry.manty.co.kr/프로젝트명:latest` |

### K8s Deployment 작성 규칙

```yaml
spec:
  template:
    spec:
      containers:
        - name: 프로젝트명
          image: registry.manty.co.kr/프로젝트명:latest
          imagePullPolicy: Always  # 외부 레지스트리 사용 시 Always
```

### K8s 리소스 배포 순서

ConfigMap, Secret 등은 Deployment보다 **먼저** 배포되어야 합니다:

1. `namespace.yaml`
2. `configmap.yaml` (Deployment에서 참조)
3. `deployment.yaml`
4. `service.yaml`
5. `ingress.yaml`

### 체크리스트

Jenkinsfile 또는 K8s 리소스 생성 시 확인사항:

- [ ] Jenkinsfile `DOCKER_REGISTRY` 값 확인
- [ ] k8s/deployment.yaml `image` 경로가 동일한 레지스트리 사용
- [ ] `imagePullPolicy: Always` 설정 (외부 레지스트리)
- [ ] ConfigMap/Secret이 Deployment보다 먼저 배포되는지 확인

## 빌드 명령

```bash
# 로컬 빌드
./mvnw clean package -DskipTests

# 테스트 포함 빌드
./mvnw clean verify

# Docker 빌드
docker build -t documentconverter-api:latest .

# 실행
./mvnw spring-boot:run
```

## sdkman 설정

이 프로젝트는 `.sdkmanrc` 파일을 사용합니다:
```
java=25.0.1-tem
```
