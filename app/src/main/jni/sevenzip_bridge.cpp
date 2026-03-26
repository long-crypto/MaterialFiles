/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

#include <jni.h>

#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/stat.h>
#include <algorithm>
#include <cctype>
#include <string>
#include <vector>

#include "CPP/Common/MyWindows.h"
#include "CPP/Common/MyInitGuid.h"
#include "CPP/Common/MyCom.h"
#include "CPP/Common/StringConvert.h"
#include "CPP/Windows/PropVariant.h"
#include "CPP/Windows/PropVariantConv.h"
#include "CPP/7zip/Common/FileStreams.h"
#include "CPP/7zip/Archive/IArchive.h"
#include "CPP/7zip/IPassword.h"
#include "CPP/7zip/PropID.h"

STDAPI CreateObject(const GUID *clsid, const GUID *iid, void **outObject);

using namespace NWindows;
using namespace NFile;

namespace {

constexpr jint kTypeUnknown = 0;
constexpr jint kTypeRegularFile = 1;
constexpr jint kTypeDirectory = 2;
constexpr jint kTypeSymbolicLink = 3;

constexpr jint kUnknownId = -1;
constexpr jint kUnknownMode = -1;
constexpr jlong kUnknownTimeMillis = LLONG_MIN;
constexpr jint kErrorOpenFailed = 1;
constexpr jint kErrorPasswordRequired = 2;
constexpr jint kErrorEntryNotFound = 3;
constexpr jint kErrorExtractionFailed = 4;

constexpr ULONGLONG kFileTimeUnixEpochOffset = 116444736000000000ULL;

#define DEFINE_GUID_ARC(name, id) \
  Z7_DEFINE_GUID(name, 0x23170F69, 0x40C1, 0x278A, 0x10, 0x00, 0x00, 0x01, 0x10, id, 0x00, 0x00)

DEFINE_GUID_ARC(CLSID_Format7z, 0x07);
DEFINE_GUID_ARC(CLSID_FormatRar, 0x03);
DEFINE_GUID_ARC(CLSID_FormatRar5, 0xCC);

struct PasswordAttempt {
  bool defined;
  UString value;
};

struct OpenArchiveResult {
  CMyComPtr<IInArchive> archive;
  CMyComPtr<IInStream> stream;
};

static jclass FindClassGlobal(JNIEnv *env, const char *name) {
  jclass localClass = env->FindClass(name);
  if (!localClass) {
    return nullptr;
  }
  jclass globalClass = static_cast<jclass>(env->NewGlobalRef(localClass));
  env->DeleteLocalRef(localClass);
  return globalClass;
}

static jclass GetSevenZipEntryDataClass(JNIEnv *env) {
  static jclass clazz = nullptr;
  if (!clazz) {
    clazz = FindClassGlobal(
        env, "me/zhanghai/android/files/provider/archive/archiver/SevenZipEntryData");
  }
  return clazz;
}

static jmethodID GetSevenZipEntryDataConstructor(JNIEnv *env) {
  static jmethodID constructor = nullptr;
  if (!constructor) {
    constructor = env->GetMethodID(
        GetSevenZipEntryDataClass(env), "<init>",
        "(Ljava/lang/String;ZJJJIJILjava/lang/String;ILjava/lang/String;ILjava/lang/String;)V");
  }
  return constructor;
}

static jclass GetSevenZipNativeExceptionClass(JNIEnv *env) {
  static jclass clazz = nullptr;
  if (!clazz) {
    clazz = FindClassGlobal(
        env, "me/zhanghai/android/files/provider/archive/archiver/SevenZipNativeException");
  }
  return clazz;
}

static jmethodID GetSevenZipNativeExceptionConstructor(JNIEnv *env) {
  static jmethodID constructor = nullptr;
  if (!constructor) {
    constructor = env->GetMethodID(
        GetSevenZipNativeExceptionClass(env), "<init>", "(ILjava/lang/String;)V");
  }
  return constructor;
}

static void ThrowSevenZipException(JNIEnv *env, jint errorCode, const char *message) {
  jstring javaMessage = message ? env->NewStringUTF(message) : nullptr;
  jobject exception = env->NewObject(
      GetSevenZipNativeExceptionClass(env), GetSevenZipNativeExceptionConstructor(env), errorCode,
      javaMessage);
  if (javaMessage) {
    env->DeleteLocalRef(javaMessage);
  }
  env->Throw(static_cast<jthrowable>(exception));
}

static std::string GetUtf8String(JNIEnv *env, jstring javaString) {
  if (!javaString) {
    return std::string();
  }
  const char *chars = env->GetStringUTFChars(javaString, nullptr);
  if (!chars) {
    return std::string();
  }
  std::string value(chars);
  env->ReleaseStringUTFChars(javaString, chars);
  return value;
}

static UString Utf8ToUString(const std::string &value) {
  return GetUnicodeString(value.c_str(), CP_UTF8);
}

static FString Utf8ToFString(const std::string &value) {
  return us2fs(Utf8ToUString(value));
}

static jstring UStringToJString(JNIEnv *env, const UString &value) {
  AString utf8;
  UnicodeStringToMultiByte2(utf8, value, CP_UTF8);
  return env->NewStringUTF(utf8.Ptr());
}

static std::vector<PasswordAttempt> GetPasswordAttempts(JNIEnv *env, jobjectArray javaPasswords) {
  std::vector<PasswordAttempt> attempts;
  const jsize count = javaPasswords ? env->GetArrayLength(javaPasswords) : 0;
  if (count == 0) {
    attempts.push_back({false, UString()});
    return attempts;
  }
  attempts.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; ++i) {
    auto javaPassword = static_cast<jstring>(env->GetObjectArrayElement(javaPasswords, i));
    std::string password = GetUtf8String(env, javaPassword);
    env->DeleteLocalRef(javaPassword);
    attempts.push_back({true, Utf8ToUString(password)});
  }
  return attempts;
}

static std::vector<const GUID *> GetCandidateFormats(const std::string &archivePath) {
  std::string lowerPath = archivePath;
  std::transform(lowerPath.begin(), lowerPath.end(), lowerPath.begin(), [](unsigned char ch) {
    return static_cast<char>(std::tolower(ch));
  });
  if (lowerPath.size() >= 3 && lowerPath.rfind(".7z") == lowerPath.size() - 3) {
    return {&CLSID_Format7z};
  }
  if ((lowerPath.size() >= 4 && lowerPath.rfind(".rar") == lowerPath.size() - 4)
      || (lowerPath.size() >= 4 && lowerPath.rfind(".r00") == lowerPath.size() - 4)) {
    return {&CLSID_FormatRar5, &CLSID_FormatRar};
  }
  return {&CLSID_Format7z, &CLSID_FormatRar5, &CLSID_FormatRar};
}

static jlong FileTimeToUnixMillis(const FILETIME &fileTime) {
  const ULONGLONG fileTimeValue =
      (static_cast<ULONGLONG>(fileTime.dwHighDateTime) << 32) | fileTime.dwLowDateTime;
  if (fileTimeValue < kFileTimeUnixEpochOffset) {
    return 0;
  }
  return static_cast<jlong>((fileTimeValue - kFileTimeUnixEpochOffset) / 10000ULL);
}

static bool GetStringProperty(IInArchive *archive, UInt32 index, PROPID propId, UString &value) {
  NCOM::CPropVariant prop;
  const HRESULT hr = archive->GetProperty(index, propId, &prop);
  if (hr != S_OK || prop.vt == VT_EMPTY) {
    value.Empty();
    return false;
  }
  if (prop.vt != VT_BSTR) {
    value.Empty();
    return false;
  }
  value = prop.bstrVal;
  return true;
}

static bool GetBoolProperty(IInArchive *archive, UInt32 index, PROPID propId, bool defaultValue) {
  NCOM::CPropVariant prop;
  const HRESULT hr = archive->GetProperty(index, propId, &prop);
  if (hr != S_OK || prop.vt == VT_EMPTY) {
    return defaultValue;
  }
  if (prop.vt != VT_BOOL) {
    return defaultValue;
  }
  return VARIANT_BOOLToBool(prop.boolVal);
}

static bool GetUInt64Property(IInArchive *archive, UInt32 index, PROPID propId, UInt64 &value) {
  NCOM::CPropVariant prop;
  const HRESULT hr = archive->GetProperty(index, propId, &prop);
  if (hr != S_OK) {
    return false;
  }
  return ConvertPropVariantToUInt64(prop, value);
}

static bool GetIntProperty(IInArchive *archive, UInt32 index, PROPID propId, jint &value) {
  UInt64 integerValue = 0;
  if (!GetUInt64Property(archive, index, propId, integerValue)) {
    return false;
  }
  value = static_cast<jint>(integerValue);
  return true;
}

static jlong GetTimePropertyMillisOrDefault(
    IInArchive *archive, UInt32 index, PROPID propId, jlong defaultValue) {
  NCOM::CPropVariant prop;
  const HRESULT hr = archive->GetProperty(index, propId, &prop);
  if (hr != S_OK || prop.vt == VT_EMPTY) {
    return defaultValue;
  }
  if (prop.vt != VT_FILETIME) {
    return defaultValue;
  }
  return FileTimeToUnixMillis(prop.filetime);
}

static jint GetEntryType(bool isDirectory, bool hasSymbolicLink, jint mode) {
  if (mode != kUnknownMode) {
    switch (mode & S_IFMT) {
      case S_IFDIR:
        return kTypeDirectory;
      case S_IFLNK:
        return kTypeSymbolicLink;
      case S_IFREG:
        return kTypeRegularFile;
      default:
        return kTypeUnknown;
    }
  }
  if (isDirectory) {
    return kTypeDirectory;
  }
  if (hasSymbolicLink) {
    return kTypeSymbolicLink;
  }
  return kTypeRegularFile;
}

class COpenCallback Z7_final : public IArchiveOpenCallback,
                               public ICryptoGetTextPassword,
                               public CMyUnknownImp {
 public:
  Z7_IFACES_IMP_UNK_2(IArchiveOpenCallback, ICryptoGetTextPassword)

  bool PasswordIsDefined = false;
  bool PasswordWasRequested = false;
  UString Password;
};

Z7_COM7F_IMF(COpenCallback::SetTotal(const UInt64 *, const UInt64 *)) {
  return S_OK;
}

Z7_COM7F_IMF(COpenCallback::SetCompleted(const UInt64 *, const UInt64 *)) {
  return S_OK;
}

Z7_COM7F_IMF(COpenCallback::CryptoGetTextPassword(BSTR *password)) {
  PasswordWasRequested = true;
  if (!PasswordIsDefined) {
    return E_ABORT;
  }
  return StringToBstr(Password, password);
}

class CSingleEntryExtractCallback Z7_final : public IArchiveExtractCallback,
                                             public ICryptoGetTextPassword,
                                             public CMyUnknownImp {
 public:
  Z7_IFACES_IMP_UNK_2(IArchiveExtractCallback, ICryptoGetTextPassword)
  Z7_IFACE_COM7_IMP(IProgress)

  bool PasswordIsDefined = false;
  bool PasswordWasRequested = false;
  UString Password;
  FString TargetPath;
  Int32 OperationResult = NArchive::NExtract::NOperationResult::kOK;

 private:
  COutFileStream *outFileStreamSpec = nullptr;
  CMyComPtr<ISequentialOutStream> outFileStream;

 public:
  Z7_COM7F_IMF(SetTotal(UInt64)) {
    return S_OK;
  }

  Z7_COM7F_IMF(SetCompleted(const UInt64 *)) {
    return S_OK;
  }

  Z7_COM7F_IMF(GetStream(UInt32, ISequentialOutStream **outStream, Int32 askExtractMode)) {
    *outStream = nullptr;
    outFileStream.Release();
    if (askExtractMode != NArchive::NExtract::NAskMode::kExtract) {
      return S_OK;
    }
    outFileStreamSpec = new COutFileStream;
    CMyComPtr<ISequentialOutStream> outStreamLoc(outFileStreamSpec);
    if (!outFileStreamSpec->Create_ALWAYS(TargetPath)) {
      return E_ABORT;
    }
    outFileStream = outStreamLoc;
    *outStream = outStreamLoc.Detach();
    return S_OK;
  }

  Z7_COM7F_IMF(PrepareOperation(Int32)) {
    return S_OK;
  }

  Z7_COM7F_IMF(SetOperationResult(Int32 operationResult)) {
    OperationResult = operationResult;
    if (outFileStream) {
      const HRESULT hr = outFileStreamSpec->Close();
      outFileStream.Release();
      return hr;
    }
    return S_OK;
  }

  Z7_COM7F_IMF(CryptoGetTextPassword(BSTR *password)) {
    PasswordWasRequested = true;
    if (!PasswordIsDefined) {
      return E_ABORT;
    }
    return StringToBstr(Password, password);
  }
};

static bool TryOpenArchive(
    const FString &archivePath, const GUID &format, const PasswordAttempt &attempt,
    OpenArchiveResult &result, bool &passwordRequested, std::string &errorMessage) {
  passwordRequested = false;
  result.archive.Release();
  result.stream.Release();
  IInArchive *archiveSpec = nullptr;
  if (CreateObject(&format, &IID_IInArchive, reinterpret_cast<void **>(&archiveSpec)) != S_OK) {
    errorMessage = "Archive handler is unavailable";
    return false;
  }
  result.archive.Attach(archiveSpec);
  auto *fileStreamSpec = new CInFileStream;
  result.stream.Attach(fileStreamSpec);
  if (!fileStreamSpec->Open(archivePath)) {
    errorMessage = "Cannot open archive file";
    result.archive.Release();
    result.stream.Release();
    return false;
  }
  CMyComPtr<COpenCallback> openCallbackSpec = new COpenCallback;
  openCallbackSpec->PasswordIsDefined = attempt.defined;
  openCallbackSpec->Password = attempt.value;
  const HRESULT hr = result.archive->Open(result.stream, nullptr, openCallbackSpec);
  passwordRequested = openCallbackSpec->PasswordWasRequested;
  if (hr != S_OK) {
    errorMessage = passwordRequested ? "Incorrect password" : "Failed to open archive";
    result.archive.Release();
    result.stream.Release();
    return false;
  }
  return true;
}

static bool IsPasswordFailure(Int32 operationResult, bool encrypted) {
  return operationResult == NArchive::NExtract::NOperationResult::kWrongPassword
      || (encrypted
          && (operationResult == NArchive::NExtract::NOperationResult::kDataError
              || operationResult == NArchive::NExtract::NOperationResult::kCRCError));
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_me_zhanghai_android_files_provider_archive_archiver_SevenZipBridge_listEntries(
    JNIEnv *env, jclass, jstring javaArchivePath, jobjectArray javaPasswords) {
  const std::string archivePathUtf8 = GetUtf8String(env, javaArchivePath);
  const FString archivePath = Utf8ToFString(archivePathUtf8);
  const std::vector<PasswordAttempt> attempts = GetPasswordAttempts(env, javaPasswords);
  const std::vector<const GUID *> formats = GetCandidateFormats(archivePathUtf8);

  bool passwordRequired = false;
  std::string errorMessage = "Failed to open archive";
  OpenArchiveResult openResult;
  bool opened = false;
  for (const GUID *format : formats) {
    for (const PasswordAttempt &attempt : attempts) {
      bool passwordRequested = false;
      if (TryOpenArchive(archivePath, *format, attempt, openResult, passwordRequested, errorMessage)) {
        opened = true;
        break;
      }
      if (passwordRequested) {
        passwordRequired = true;
      }
    }
    if (opened) {
      break;
    }
  }
  if (!opened) {
    ThrowSevenZipException(
        env, passwordRequired ? kErrorPasswordRequired : kErrorOpenFailed, errorMessage.c_str());
    return nullptr;
  }

  UInt32 numItems = 0;
  if (openResult.archive->GetNumberOfItems(&numItems) != S_OK) {
    ThrowSevenZipException(
        env, kErrorOpenFailed, "Failed to enumerate archive entries");
    return nullptr;
  }

  jclass entryClass = GetSevenZipEntryDataClass(env);
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(numItems), entryClass, nullptr);
  if (!result) {
    return nullptr;
  }

  for (UInt32 index = 0; index < numItems; ++index) {
    UString name;
    GetStringProperty(openResult.archive, index, kpidPath, name);
    const bool isEncrypted = GetBoolProperty(openResult.archive, index, kpidEncrypted, false);
    const bool isDirectory = GetBoolProperty(openResult.archive, index, kpidIsDir, false);
    UInt64 sizeValue = 0;
    if (!GetUInt64Property(openResult.archive, index, kpidSize, sizeValue)) {
      sizeValue = 0;
    }
    jint userId = kUnknownId;
    GetIntProperty(openResult.archive, index, kpidUserId, userId);
    UString userName;
    const bool hasUserName = GetStringProperty(openResult.archive, index, kpidUser, userName);
    jint groupId = kUnknownId;
    GetIntProperty(openResult.archive, index, kpidGroupId, groupId);
    UString groupName;
    const bool hasGroupName = GetStringProperty(openResult.archive, index, kpidGroup, groupName);
    jint mode = kUnknownMode;
    if (!GetIntProperty(openResult.archive, index, kpidPosixAttrib, mode)) {
      GetIntProperty(openResult.archive, index, kpidAttrib, mode);
    }
    UString symbolicLinkTarget;
    const bool hasSymbolicLink =
        GetStringProperty(openResult.archive, index, kpidSymLink, symbolicLinkTarget);
    const jint type = GetEntryType(isDirectory, hasSymbolicLink, mode);

    jstring javaName = UStringToJString(env, name);
    jstring javaUserName = hasUserName ? UStringToJString(env, userName) : nullptr;
    jstring javaGroupName = hasGroupName ? UStringToJString(env, groupName) : nullptr;
    jstring javaSymbolicLinkTarget =
        hasSymbolicLink ? UStringToJString(env, symbolicLinkTarget) : nullptr;
    jobject javaEntry = env->NewObject(
        entryClass, GetSevenZipEntryDataConstructor(env), javaName, static_cast<jboolean>(isEncrypted),
        GetTimePropertyMillisOrDefault(openResult.archive, index, kpidMTime, kUnknownTimeMillis),
        GetTimePropertyMillisOrDefault(openResult.archive, index, kpidATime, kUnknownTimeMillis),
        GetTimePropertyMillisOrDefault(openResult.archive, index, kpidCTime, kUnknownTimeMillis),
        type, static_cast<jlong>(sizeValue), userId, javaUserName, groupId, javaGroupName, mode,
        javaSymbolicLinkTarget);
    env->SetObjectArrayElement(result, static_cast<jsize>(index), javaEntry);
    env->DeleteLocalRef(javaName);
    if (javaUserName) {
      env->DeleteLocalRef(javaUserName);
    }
    if (javaGroupName) {
      env->DeleteLocalRef(javaGroupName);
    }
    if (javaSymbolicLinkTarget) {
      env->DeleteLocalRef(javaSymbolicLinkTarget);
    }
    env->DeleteLocalRef(javaEntry);
    if (env->ExceptionCheck()) {
      return nullptr;
    }
  }
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_me_zhanghai_android_files_provider_archive_archiver_SevenZipBridge_extractEntry(
    JNIEnv *env, jclass, jstring javaArchivePath, jobjectArray javaPasswords, jstring javaEntryName,
    jstring javaTargetPath) {
  const std::string archivePathUtf8 = GetUtf8String(env, javaArchivePath);
  const FString archivePath = Utf8ToFString(archivePathUtf8);
  const std::string targetPathUtf8 = GetUtf8String(env, javaTargetPath);
  const FString targetPath = Utf8ToFString(targetPathUtf8);
  const UString entryName = Utf8ToUString(GetUtf8String(env, javaEntryName));
  const std::vector<PasswordAttempt> attempts = GetPasswordAttempts(env, javaPasswords);
  const std::vector<const GUID *> formats = GetCandidateFormats(archivePathUtf8);

  bool passwordRequired = false;
  bool entryFound = false;
  std::string errorMessage = "Failed to extract archive entry";

  for (const GUID *format : formats) {
    for (const PasswordAttempt &attempt : attempts) {
      OpenArchiveResult openResult;
      bool passwordRequested = false;
      if (!TryOpenArchive(archivePath, *format, attempt, openResult, passwordRequested, errorMessage)) {
        if (passwordRequested) {
          passwordRequired = true;
        }
        continue;
      }

      UInt32 numItems = 0;
      if (openResult.archive->GetNumberOfItems(&numItems) != S_OK) {
        ThrowSevenZipException(
            env, kErrorExtractionFailed,
            "Failed to enumerate archive entries");
        return;
      }

      for (UInt32 index = 0; index < numItems; ++index) {
        UString currentName;
        GetStringProperty(openResult.archive, index, kpidPath, currentName);
        if (currentName != entryName) {
          continue;
        }
        entryFound = true;
        const bool encrypted = GetBoolProperty(openResult.archive, index, kpidEncrypted, false);

        CMyComPtr<CSingleEntryExtractCallback> extractCallback = new CSingleEntryExtractCallback;
        extractCallback->PasswordIsDefined = attempt.defined;
        extractCallback->Password = attempt.value;
        extractCallback->TargetPath = targetPath;

        const UInt32 indices[] = {index};
        const HRESULT hr = openResult.archive->Extract(indices, 1, false, extractCallback);
        if (hr == S_OK
            && extractCallback->OperationResult == NArchive::NExtract::NOperationResult::kOK) {
          return;
        }

        ::remove(targetPathUtf8.c_str());
        if (extractCallback->PasswordWasRequested
            && IsPasswordFailure(extractCallback->OperationResult, encrypted)) {
          passwordRequired = true;
          break;
        }

        ThrowSevenZipException(
            env, kErrorExtractionFailed,
            "Failed to extract archive entry");
        return;
      }
    }
  }

  if (!entryFound) {
    ThrowSevenZipException(
        env, kErrorEntryNotFound, "Archive entry not found");
    return;
  }

  ThrowSevenZipException(
      env, passwordRequired ? kErrorPasswordRequired : kErrorExtractionFailed,
      passwordRequired ? "Incorrect password" : errorMessage.c_str());
}
