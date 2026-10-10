# Win11 Miracast source：約 1 Hz 突發送幀

## 症狀（sink 實測）

在 HA1CSQTM（Phh-Treble + Mirax）上對任意 Windows Host 連線時，RTP 呈現固定節奏：

| 指標 | 實測 |
|------|------|
| 協商 | 曾為 `1280×720@30`（interactive 只廣播 ≤30 Hz） |
| 每波 AU | 剛好 **~30**（一秒的名義幀） |
| 波間空隙 | **~540–590 ms**（`RTP gap_ms`） |
| 週期 | **~1.0 s** |
| 解碼延遲 | `submit_to_frame_ms` ≈ 7–11 ms（sink 端不是瓶頸） |
| 碼率 | 活動時多卡在 **~2–3 Mbps**（偶發尖峰仍低於 4 Mbps） |

呈現側 hitch 與 RTP 空隙對齊 → **卡頓來自來源送幀節奏，不是解碼器排隊**。

## 共因：這台 sink 連線，不是某一台 Host

- Fold 5（Samsung 原廠）同一套 Windows **正常**
- 換另一台 Windows PC，**只有這台 Phh + Mirax** 仍出現 ~550 ms 空隙與 2–3 Mbps

因此要查的是 **Mirax 在這台上讓 Host 怎麼解讀延遲／網路／能力**，不是推給某一台 PC。

## 根因鏈（2026-10-09 對齊）

```text
M3 回答 latency 能力
        ↓
Host 每場 SET_PARAMETER: microsoft_latency_management_capability: high
        ↓
High 角色允許 sink 緩衝上限 ~500 ms（MS 文件）
        ↓
來源改為「約 1s 內容壓成半秒送出 + ~550 ms 靜默」
        ↓
Mirax ASAP 立刻顯示 → 使用者感到約一秒卡一下
同時 720p30 + High/media encode → ABR 工作點落在 2–3 Mbps
```

### 證據

1. **Host 每場宣告 High**（多場 log）：
   - `RTSP << SET_PARAMETER … microsoft_latency_management_capability: high`
   - `source latency mode high`
2. **M3 回 `low` 無效**：sink 多次 `RTSP >> latency mode low`，來源從未改成 `low`（`source still high`）。
3. **RTCP 修好後仍壓碼率**：
   - RR → `192.168.235.248:7492`，`frac=0`，`lost≈0–3`
   - tcpdump：RR 與 SR 雙向通（`19001↔7492`）
   - 仍見 `RTP gap_ms≈550` 與 `bitrate_kbps≈2xxx`
4. **解碼不是瓶頸**：`submit_to_frame_ms` 個位數～十幾 ms；空隙與 AU 突發對齊。

### 與 Microsoft 文件對齊

[Wireless projection receiver manufacturers](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/wireless-projection-receiver-manufacturers)：

| Role | 目標 |
|------|------|
| Low | < 50 ms |
| Normal | < 100 ms |
| High | 可額外緩衝，**上限約 500 ms** |

實測空隙 ~550 ms 與 High 量級一致。Host 在 High／媒體內容路徑下會把約 1 秒內容壓成半秒內送出再靜默。

[Real-time bitrate modulation](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/wireless-projection-receiver-manufacturers) 用 RTCP 調碼率；**錯誤 RR 曾把 ABR 鎖死**，但修乾淨後仍停在 2–3 Mbps → 主因是 **High + 低解析度 encode 工作點**，不只是丟包回報。

## Sink 側已確認的缺陷（與修復）

| 缺陷 | 狀態 |
|------|------|
| `setRemoteRtcpPort` 被 `start()` 內 `stop()` 清掉 → RR 沒送出 | 已修：`start(local, remote)` |
| RR 目的 IP ≠ RTP 來源 | 已修：RR 跟隨 RTP peer |
| jitter 用 elapsedRealtime 混 RTP timestamp → 虛高 | 已修：jitter=0 |
| SSRC 切換被算成大量丟包 | 已修：SSRC 重置 |
| RTP rcvbuf 過小 → 突發丟包 | 已修：1 MB |
| M3 回 `low` 仍被 Host SET `high` | 未解；mid-session 催 low 會卡住 encode |
| interactive 解析度硬鎖 | **已撤回**：曾鎖 720p30／1080p30，覆寫 Settings；現改回完全尊重 `offer.modes` / preferred |

## 這台裝置特有因素（相對 Fold 5）

- Phh-Treble GO：`DIRECT-*-Phh-Treble`，同時 STA 連 Homie（5 GHz）→ 可能 MCC 占空
- Mirax 曾強制 interactive ≤720p30（Fold 原生常更高解析／幀率）
- Mirax ASAP 顯示（不為 High 緩衝）→ High 節奏直接變成視覺 hitch；Fold 原生 sink 會緩衝 High

## 對 Mirax 的含義

| 做法 | 結果 |
|------|------|
| ASAP 顯示 | 低延遲，但 High 突發 → 卡一下 |
| Sink 緩衝 ~0.5–1 s | 平滑，但變成穩定延遲（已撤回） |
| 修 RTCP | 必要，但不夠把 Host 拉出 High |
| 宣告 latency `none` + 提高解析度 | 目標：阻止 High 宣告並提高 ABR 工作點 |

要根治需讓 Windows **不要進入 High／媒體突發排程**（能力宣告、內容型態、或真正談成 low），並給 encode 足夠解析度／碼率頭空間。
