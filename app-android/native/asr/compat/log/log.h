// fdk-aac (AOSP copy) logs two SBR security events through the platform-private liblog API; not available
// to NDK apps, so they become no-ops here.
#pragma once
#define android_errorWriteLog(tag, subTag) ((void)0)
