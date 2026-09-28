# Device Owner 與 CONFIGURE_WIFI_DISPLAY

研究日期：2026-09-28。這次沒有接上手機，所以沒有重做裝置 dump。Fold 5 上 shell 可以、一般 APK 不行，沿用 [daemon-privilege.md](daemon-privilege.md) 的既有紀錄。

## 結論

Device Owner 拿不到 `CONFIGURE_WIFI_DISPLAY`。自己當 Device Owner，或用 `DevicePolicyManager.setPermissionGrantState()` 授給別的套件，都過不了這道檢查。`WifiP2pManager.setWfdInfo()` 仍看呼叫端 UID 是否已持有該權限。

Device Owner 不改套件的簽章，也不改 UID。這項權限的授予只看平台簽章或映像裡預先列出的 known signer。

## 權限怎麼定義

AOSP `android-14.0.0_r1`、`android-15.0.0_r1`、`android-16.0.0_r1` 的 `frameworks/base/core/res/AndroidManifest.xml` 都是：

```xml
<permission android:name="android.permission.CONFIGURE_WIFI_DISPLAY"
    android:protectionLevel="signature|knownSigner"
    android:knownCerts="@array/wifi_known_signers" />
```

沒有 `privileged`，也沒有 `dangerous`。放進 `priv-app` 不會因此通過。`signature` 只授給與定義者同一張平台憑證簽過的套件。`knownSigner` 只授給 `knownCerts` 裡列出的憑證摘要。

AOSP `core/res/res/values/arrays.xml` 的 `wifi_known_signers` 是空陣列。OEM 可以在系統映像覆寫這個陣列，但那是出廠資源，不是執行期政策。Device Owner 沒有 API 能往裡加憑證。

第三方 DPC 的簽章不在平台憑證上。就算 Samsung 在這台 Fold 的 overlay 放了自己的憑證，那也只會讓 Samsung 簽過的套件通過，不會讓後來裝上的 Device Owner 通過。這台韌體的 `wifi_known_signers` 內容這次沒有 dump。

## Device Owner 能授的權限

`DevicePolicyManagerService.setPermissionGrantState()`（AOSP `android-15.0.0_r1`）先走 `canGrantPermission()`。那裡的 `isRuntimePermission()` 只接受基底保護等級為 `PROTECTION_DANGEROUS` 的權限：

```java
return (permissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
        == PermissionInfo.PROTECTION_DANGEROUS;
```

`CONFIGURE_WIFI_DISPLAY` 的基底是 `signature`，所以 `canGrantPermission()` 回傳 false，callback 送 `null`，公開 API 的結果是沒有授予。後面實際呼叫的是 `PermissionControllerManager.setRuntimePermissionGrantStateByDeviceAdmin()`，名稱與實作都限 runtime permission。

`PERMISSION_POLICY_AUTO_GRANT` 同樣只涵蓋之後的 runtime permission 請求，不涵蓋 signature 權限。

## 為什麼 shell 可以

`WifiP2pServiceImpl.getWfdPermission()` 用呼叫端 UID 查 `CONFIGURE_WIFI_DISPLAY`，不看行程是不是 Device Owner。

AOSP `packages/Shell/AndroidManifest.xml` 宣告了 `android:sharedUserId="android.uid.shell"`，並 `uses-permission` 這項權限。`com.android.shell` 由平台憑證簽署，所以 UID 2000 的 `app_process` 查得到。Device Owner 是另一個 UID，查不到。

## 和 Knox 的界線

Knox Device Owner 若能打開 Samsung 自己的元件，跑的是 Samsung 已持有的簽章。專案自己的行程仍是 Device Owner 那個 UID，仍然沒有 `CONFIGURE_WIFI_DISPLAY`。見 [privileged-entrypoints.md](privileged-entrypoints.md)。

完全受管註冊可能清除手機。這份結論不需要做那次註冊。

## 來源

- AOSP `frameworks/base/core/res/AndroidManifest.xml`，`android-14.0.0_r1`、`android-15.0.0_r1`、`android-16.0.0_r1`
- AOSP `frameworks/base/core/res/res/values/arrays.xml` 的 `wifi_known_signers`，`android-15.0.0_r1`
- AOSP `services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java` 的 `canGrantPermission()`、`isRuntimePermission()`，`android-15.0.0_r1`
- AOSP `packages/Shell/AndroidManifest.xml`，`android-15.0.0_r1`
- [Android `DevicePolicyManager.setPermissionGrantState()`](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setPermissionGrantState(android.content.ComponentName,%20java.lang.String,%20java.lang.String,%20int))
