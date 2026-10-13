#pragma once

#ifdef __cplusplus
extern "C" {
#endif

// Entry point exported by the generated aggregate C-ABI user bridge
// (librdma_user.a). Registers the aggregate installer/object-wrapper with the
// runtime; called once before rdmaStart().
void rdma_userBridgeInstall(void);

#ifdef __cplusplus
}
#endif
