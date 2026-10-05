#pragma once
#include <jsi/jsi.h>
#include "RdmaComposeCAbi.h"

namespace facebook {
namespace rdma {

jsi::Object makeComposerProxy(jsi::Runtime& rt);

} // namespace rdma
} // namespace facebook
