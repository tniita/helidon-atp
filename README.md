 # Helidon OCI SDKによるATP接続サンプルコード
 
## 環境構築

1. OKE環境の構築します  
[こちら](https://oracle-japan.github.io/ocitutorials/cloud-native/oke-for-commons/)をご確認ください。

2. ATPの構築します  
[こちら](https://oracle-japan.github.io/ocitutorials/database/adb101-provisioning/)をご確認ください。

3. Secretの作成します  
アプリケーションが利用するATPの情報をSecretリソースとして作成します。

```sh
kubectl create secret generic atp-secret --from-literal=tnsNetServiceName=<servicename> --from-literal=password=<ユーザのpassword>  --from-literal=<ATPのOCID> --from-literal=atp-walletPassword=<Adminユーザのパスワード> --from-literal=user=<ユーザ名>
```

4. Manigfestをデプロイします  

```sh
kubectl apply -f app.yaml
```

5. 環境を確認します。 

```sh
kubectl get pods,svc
```

以下のように出力されます。

```sh
$ kubectl get pods,svc
NAME                               READY   STATUS    RESTARTS   AGE
pod/helidon-atp-6dc978dd86-v9v79   1/1     Running   0          11m

NAME                  TYPE           CLUSTER-IP    EXTERNAL-IP     PORT(S)             AGE
service/helidon-atp   LoadBalancer   10.96.79.57   yyy.yyy.yyy.yyy   80:31472/TCP        18h
service/kubernetes    ClusterIP      10.96.0.1     <none>          443/TCP,12250/TCP   25h
```

EXTERNAL-IP(上記の場合は`yyy.yyy.yyy.yyy`)をメモします。  

6. 動作確認をします  

5で確認したEXTERNAL-IPを利用してアプリケーションにアクセスします。  

ブラウザから以下にアクセスします。

http://yyy.yyy.yyy.yyy/atp/wallet

