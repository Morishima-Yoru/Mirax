# 參考用舊程式

這裡只留已被目前接收端取代、而且是這個專案自己寫的程式。

`player/ShellControl.java` 是播放器主動連到 shell 的舊方向。這台裝置上那條連線被擋住，現在改由 `receiver/player/ShellBridge.java` 聽 port，shell 端連進來。

會啟動 Samsung `SecondScreenPlayer` 的 daemon、SmartMirroring 的反編譯，以及對 native library 的 hook，沒有放進這個目錄。
