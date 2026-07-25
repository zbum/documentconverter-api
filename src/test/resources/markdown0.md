## 개요
* K8s 에서 Persistent Volume 은 인프라 담당자가 만드는 것이고 Persistent Volume Claim 은 애플리케이션 개발자가 Persistent Volume 을 사용하겠다고 선언하는 것이다.
* Persistent Volume 과 Persistent Volume Claim 은 binding 으로 연결되고 이 부분은 1 대 1 관계이다.
* NAS 가 없는 홈서버에서 K8s 를 운영하려면 단일 서버인 경우,  HostPath 를 써도 되지만 일반적인 helm chart 는  Persistent Volume을 요구한다. (HostPath 는 보통 보안적으로도 문제가 있다.)
* 그것도 StorageClass 를 이용해서 자동으로 Persistent Volume 을 생성하는 Provisioner 가 있을때 잘 설치되는 구성이 많다.
* 이는 managed K8s cluster 에서 대부분 제공하고 있기에 가난한 On-Premise K8s 는 신경써 주지 않는 것 같다 .
* 여기서는 집에서 구성한 On-Premise K8s 에서 NFS 와 nfs-subdir-external-provisioner 를 사용해서 managed K8s cluster 와 같은 구성을 설정해 본다.

## ubuntu 24 에 nfs 서버 설치하기
* nfs server 를 설치하기 위해서는 패키지를 몇가지 설치해야 한다.
```
sudo apt-get update
sudo apt-get install -y nfs-common nfs-kernel-server rpcbind portmap
```

* 이렇게 설치된 nfs server 를 설정하자. 먼저 파일이 저장될 디렉토리를 하나 만든다.
```
sudo mkdir /nas
sudo chown nobody:nogroup /nas
sudo chmod 777 /nas
```
* /etc/export 파일에 /nas 의 위치를 알려 준다.
* 접근 가능한 IP 주소를 CIDR로 설정하는데. 지금 내 서버의 IP 는 192.168.31.X 이니까. 192.168.31 로 시작하는 IP 는 모두 접근하도록 192.168.31.0/24 를 지정한다.
```
sudo vi /etc/exports
```
* 파일의 최하단에 다음을 추가
```
/nas 192.168.31.0/24(rw,sync,no_subtree_check)
```
* rw : 읽기와 쓰기 권한을 허용
* sync : nfs 클라이언트의 요청에 응답하기 전에 서버가 디스크에 동기화 합니다 .
* no_subtree_check : 서브디렉토리 검사를 비활성화 하여 성능을 향상시킵니다.

* 이제 nfs 서버를 재시작합니다.
```
sudo exportfs -a
sudo systemctl restart nfs-kernel-server
```

## nfs-subdir-external-provisioner 설치 구성하기
* helm 이 설치되어 있다고 가정하면 다음 명령어로 helm repository 를 설정할 수 있다.
```
helm repo add nfs-subdir-external-provisioner https://kubernetes-sigs.github.io/nfs-subdir-external-provisioner/
```
* 이제 helm 으로 설치를 하는데. NFS 의 서버 주소를 알려 주어야 한다.
* 나는 주로  kube-sysytem 네임스페이스에 설치하는 것을 선호한다.
```
helm install nfs-subdir-external-provisioner nfs-subdir-external-provisioner/nfs-subdir-external-provisioner \
    --set nfs.server={서버의 IP 주소} \
    --set nfs.path=/nas \
    -n kube-system
```

* 설치 확인
```
$ kubectl get pod -n kube-system
NAME                                               READY   STATUS    RESTARTS      AGE
coredns-66bc5c9577-kflgl                           1/1     Running   5 (50m ago)   31d
coredns-66bc5c9577-zrw56                           1/1     Running   5 (50m ago)   31d
etcd-manty-macbookpro                              1/1     Running   5 (50m ago)   31d
kube-apiserver-manty-macbookpro                    1/1     Running   9 (50m ago)   31d
kube-controller-manager-manty-macbookpro           1/1     Running   7 (50m ago)   31d
kube-proxy-sfkkx                                   1/1     Running   5 (50m ago)   31d
kube-scheduler-manty-macbookpro                    1/1     Running   7 (50m ago)   31d
metrics-server-799f7ccf68-b8qz8                    1/1     Running   1 (50m ago)   16d
nfs-subdir-external-provisioner-649cff97dc-g7whw   1/1     Running   0             39s
tiller-deploy-5b97649757-tst6h                     1/1     Running   5 (50m ago)   31d
```

* 로그 확인
```
k logs -f nfs-subdir-external-provisioner-649cff97dc-g7whw -n kube-system
I1110 13:25:57.581404       1 leaderelection.go:242] attempting to acquire leader lease  kube-system/cluster.local-nfs-subdir-external-provisioner...
I1110 13:25:57.598396       1 leaderelection.go:252] successfully acquired lease kube-system/cluster.local-nfs-subdir-external-provisioner
I1110 13:25:57.598617       1 controller.go:820] Starting provisioner controller cluster.local/nfs-subdir-external-provisioner_nfs-subdir-external-provisioner-649cff97dc-g7whw_9893e1e8-32b1-421c-a18e-8a87a653aacb!
I1110 13:25:57.598654       1 event.go:278] Event(v1.ObjectReference{Kind:"Endpoints", Namespace:"kube-system", Name:"cluster.local-nfs-subdir-external-provisioner", UID:"d5c16265-115a-40fe-bd8e-eca33efec915", APIVersion:"v1", ResourceVersion:"3307419", FieldPath:""}): type: 'Normal' reason: 'LeaderElection' nfs-subdir-external-provisioner-649cff97dc-g7whw_9893e1e8-32b1-421c-a18e-8a87a653aacb became leader
I1110 13:25:57.699104       1 controller.go:869] Started provisioner controller cluster.local/nfs-subdir-external-provisioner_nfs-subdir-external-provisioner-649cff97dc-g7whw_9893e1e8-32b1-421c-a18e-8a87a653aacb!
```

* storageClass 도 확인해야 한다. provisioner 를 사용하는데 어떤 구현체를 쓸것인가를 알려주어야 하기 때문이다.

```
k get storageClass
NAME         PROVISIONER                                     RECLAIMPOLICY   VOLUMEBINDINGMODE   ALLOWVOLUMEEXPANSION   AGE
nfs-client   cluster.local/nfs-subdir-external-provisioner   Delete          Immediate           true                   5m21s
```
* 자동으로 nfs-client 라는 storageClass 가 만들어 졌다.

## 테스트
* Persistent Volume Claim 으로 바로 Persistet Volume 생성하고 바인드되는 것 확인 해보자.

* 간단한 persistent volume claim 을 생성한다.
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: test-pvc
spec:
  accessModes:
    - ReadWriteMany
  storageClassName: nfs-client
  resources:
    requests:
      storage: 2Gi
```
* 다음 명령으로 pvc 를 만들고 결과를 확인한다.
```
$ kubectl apply -f pvc.yaml
persistentvolumeclaim/test-pvc created
$ kubectl get pvc
NAME       STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS   VOLUMEATTRIBUTESCLASS   AGE
test-pvc   Bound    pvc-f0ee6fb9-d949-4784-af91-c9466970b21b   2Gi        RWX            nfs-client     <unset>                 61s
```
* /nas 디렉토리를 살펴보면 새로운 디렉토리가 만들어져 있다.
```
$ ls /nas
default-test-pvc-pvc-f0ee6fb9-d949-4784-af91-c9466970b21b
```
* 이제 deployment 를 사용해서 test-pvc 를 volume 으로 사용해보자.
* 다음과 같이 설정하면 nas 의 서브디렉토리가 만들어지고 그 디렉토리는 컨테이너 내에 /data 로 마운트 된다.
* busybox 라는 이미지에서 /data/out.txt 에 5초에 한번 시간을 작성해 줄 것이다.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: test-volume-deployment
spec:
  replicas: 1
  selector:
    matchLabels:
      app: volume-test
  template:
    metadata:
      labels:
        app: volume-test
    spec:
      containers:
      - name: busybox
        image: busybox:stable
        command: ["/bin/sh", "-c", "while true; do echo $(date) >> /data/out.txt; sleep 5; done"]
        volumeMounts:
        - name: data-vol
          mountPath: /data
      volumes:
      - name: data-vol
        persistentVolumeClaim:
          claimName: test-pvc
```

* deployment 를 K8s 클러스터에 적용하고
```
$ kubectl apply -f deploy.yaml
deployment.apps/test-volume-deployment created
```
* /nas/default-test-pvc-pvc-f0ee6fb9-d949-4784-af91-c9466970b21b 디렉토리를 살펴보면 파일이 하나 만들어 져 있다.
```
$ ls
out.txt
$ tail -f out.txt
Mon Nov 10 13:41:54 UTC 2025
Mon Nov 10 13:41:59 UTC 2025
Mon Nov 10 13:42:04 UTC 2025
Mon Nov 10 13:42:09 UTC 2025
Mon Nov 10 13:42:14 UTC 2025
Mon Nov 10 13:42:19 UTC 2025
Mon Nov 10 13:42:24 UTC 2025
Mon Nov 10 13:42:29 UTC 2025
Mon Nov 10 13:42:34 UTC 2025
Mon Nov 10 13:42:39 UTC 2025
Mon Nov 10 13:42:44 UTC 2025
... 계속....

````
* tail 로 살펴보면 file 의 내용이 점점 늘어나는 것을 알 수 있다.

* 이제 동작은 확인했으니 deployment 와 pvc 를 삭제하자.
```
$ k delete deployments.apps test-volume-deployment
deployment.apps "test-volume-deployment" deleted from default namespace
$ k delete pvc test-pvc
persistentvolumeclaim "test-pvc" deleted from default namespace
```

* 이제 /nas 디렉토리를 살펴보면 디렉토리 이름이 archived 로 시작하도록 변경되었을 것이다.
```
$ ls -al /nas
total 12
drwxrwxrwx  3 nobody nogroup 4096 Nov 10 22:45 .
drwxr-xr-x 24 root   root    4096 Nov 10 21:58 ..
drwxrwxrwx  2 nobody nogroup 4096 Nov 10 22:40 archived-default-test-pvc-pvc-f0ee6fb9-d949-4784-af91-c9466970b21b
```

## 참고 링크
* [https://kubernetes-sigs.github.io/nfs-subdir-external-provisioner/](https://kubernetes-sigs.github.io/nfs-subdir-external-provisioner/)
