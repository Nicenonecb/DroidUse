# APK-side ROM client

This Gradle library generates `dev.droiduse.systemclient` from the canonical
`system-api` AIDL and Java sources. Do not maintain a second handwritten protocol.
The two interface descriptors remain `dev.droiduse.system.IDroidUseSystem` and
`dev.droiduse.system.IDroidUseCallback`; parcelable field order is unchanged.

The framework contains hidden `dev.droiduse.system` classes. Packaging classes
with those same names in an APK does not override the boot class loader: Android
36 rejects their hidden methods/fields, producing `NoSuchMethodError` even when
the service is present. The client uses the public `IInterface.asBinder()` on the
object returned by `Context.getSystemService("droiduse")`, then wraps the binder
with its generated APK proxy. No hidden API exemption is needed.

The [AIDL Descriptor annotation](https://source.android.com/docs/core/architecture/aidl/aidl-annotations#descriptor)
preserves wire identity across the renamed Java interfaces. ROM-side UID, package,
signature, user and session checks remain authoritative.
