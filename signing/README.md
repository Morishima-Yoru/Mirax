# AOSP platform test signing

`aosp-platform.jks` is the public AOSP **platform** test key
(`build/target/product/security/platform.*`), password `android`, alias `platform`.

Use the `platform` build type on devices whose system image is signed with
`test-keys` (for example Phh-Treble / Lineage GSI). That signature matches the
ROM platform cert, so declaring `CONFIGURE_WIFI_DISPLAY` is granted and Mirax
can advertise a Miracast sink without shell/Shizuku.

Do **not** use this build type on OEM release-keys phones; the signature will
not match and the permission will not be granted.
