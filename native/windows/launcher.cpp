#include <windows.h>
#include <shellapi.h>
#include <jni.h>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;

namespace {
std::wstring environment(const wchar_t* name) {
    const DWORD size = GetEnvironmentVariableW(name, nullptr, 0);
    if (!size) return {};
    std::wstring value(size, L'\0');
    const DWORD copied = GetEnvironmentVariableW(name, value.data(), size);
    if (!copied || copied >= size) throw std::runtime_error("Could not read the process environment");
    value.resize(copied);
    return value;
}

std::string utf8(const std::wstring& value) {
    if (value.empty()) return {};
    const int length = static_cast<int>(value.size());
    const int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value.data(), length, nullptr, 0, nullptr, nullptr);
    if (!size) throw std::runtime_error("Invalid Unicode in a JVM option");
    std::string result(size, '\0');
    if (!WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value.data(), length, result.data(), size, nullptr, nullptr))
        throw std::runtime_error("Could not encode a JVM option");
    return result;
}

fs::path javaHome() {
    auto home = environment(L"JAVA_HOME");
    if (!home.empty()) {
        if (home.front() == L'"' && home.back() == L'"') home = home.substr(1, home.size() - 2);
        if (!fs::is_regular_file(fs::path(home) / L"bin/server/jvm.dll"))
            throw std::runtime_error("JAVA_HOME must select a Java 21 or newer x64 HotSpot installation");
        return fs::absolute(home);
    }
    const auto path = environment(L"PATH");
    std::vector<wchar_t> java(32768);
    const DWORD size = path.empty() ? 0 : SearchPathW(path.c_str(), L"java.exe", nullptr,
        static_cast<DWORD>(java.size()), java.data(), nullptr);
    if (!size || size >= java.size())
        throw std::runtime_error("Java was not found; set JAVA_HOME to a Java 21 or newer x64 HotSpot installation");
    const auto found = fs::canonical(fs::path(java.data())).parent_path().parent_path();
    if (!fs::is_regular_file(found / L"bin/server/jvm.dll"))
        throw std::runtime_error("Java on PATH is not an x64 HotSpot installation; set JAVA_HOME explicitly");
    return found;
}

std::string classPath() {
    std::vector<wchar_t> executable(32768);
    const DWORD size = GetModuleFileNameW(nullptr, executable.data(), static_cast<DWORD>(executable.size()));
    if (!size || size >= executable.size()) throw std::runtime_error("Could not locate the command installation");
    const auto directory = fs::path(executable.data()).parent_path().parent_path() / L"lib";
    std::vector<fs::path> jars;
    for (const auto& entry : fs::directory_iterator(directory)) {
        if (entry.is_regular_file() && entry.path().extension() == L".jar") jars.push_back(entry.path());
    }
    if (jars.empty()) throw std::runtime_error("The installation has no libraries; extract the complete Windows ZIP");
    std::sort(jars.begin(), jars.end());
    std::wstring result;
    for (const auto& jar : jars) {
        if (!result.empty()) result += L';';
        result += jar.wstring();
    }
    return utf8(result);
}

void appendOptions(std::vector<std::string>& options, const wchar_t* name) {
    const auto value = environment(name);
    if (value.empty()) return;
    const auto line = L"scouterctl " + value;
    int count = 0;
    wchar_t** parsed = CommandLineToArgvW(line.c_str(), &count);
    if (!parsed) throw std::runtime_error("Could not parse JVM options");
    try {
        for (int i = 1; i < count; ++i) options.push_back(utf8(parsed[i]));
    } catch (...) { LocalFree(parsed); throw; }
    LocalFree(parsed);
}

struct JavaFailure {};

template<typename T> T required(JNIEnv* env, T result) {
    if (env->ExceptionCheck()) throw JavaFailure{};
    if (!result) throw std::runtime_error("Could not initialize the CLI entry point");
    return result;
}

int invokeMain(JavaVM* vm, JNIEnv* env, int argc, wchar_t** argv) {
    int result = 0;
    try {
        const auto main = required(env, env->FindClass("dev/scouterctl/MainKt"));
        const auto string = required(env, env->FindClass("java/lang/String"));
        const auto method = required(env, env->GetStaticMethodID(main, "main", "([Ljava/lang/String;)V"));
        const auto arguments = required(env, env->NewObjectArray(argc - 1, string, nullptr));
        for (int i = 1; i < argc; ++i) {
            const auto value = required(env, env->NewString(reinterpret_cast<const jchar*>(argv[i]),
                static_cast<jsize>(wcslen(argv[i]))));
            env->SetObjectArrayElement(arguments, i - 1, value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) throw JavaFailure{};
        }
        env->CallStaticVoidMethod(main, method, arguments);
        if (env->ExceptionCheck()) throw JavaFailure{};
    } catch (const JavaFailure&) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        result = 1;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "scouterctl: %s\n", error.what());
        result = 1;
    }
    // Kotlin normally calls System.exit; this handles an uncaught startup error or a returning main.
    if (vm->DestroyJavaVM() != JNI_OK) result = 1;
    return result;
}
}

int wmain(int argc, wchar_t** argv) {
    static_assert(sizeof(wchar_t) == sizeof(jchar));
    try {
        if (GetACP() != CP_UTF8)
            throw std::runtime_error("The Windows launcher requires Windows 10 version 1903 or newer and its embedded UTF-8 manifest");
        if (!environment(L"JDK_JAVA_OPTIONS").empty())
            throw std::runtime_error("JDK_JAVA_OPTIONS is a java.exe setting; use JAVA_TOOL_OPTIONS for JVM options with scouterctl.exe");
        const auto home = javaHome();
        if (!SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32 | LOAD_LIBRARY_SEARCH_USER_DIRS) ||
            !AddDllDirectory((home / L"bin").c_str()))
            throw std::runtime_error("Could not configure the Java library search path");
        const HMODULE library = LoadLibraryExW((home / L"bin/server/jvm.dll").c_str(), nullptr,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32 | LOAD_LIBRARY_SEARCH_USER_DIRS);
        if (!library) throw std::runtime_error("Could not load Java; select a Java 21 or newer x64 HotSpot installation");
        using CreateVM = jint(JNICALL*)(JavaVM**, void**, void*);
        const auto address = GetProcAddress(library, "JNI_CreateJavaVM");
        CreateVM create = nullptr;
        static_assert(sizeof(create) == sizeof(address));
        std::memcpy(&create, &address, sizeof(create));
        if (!create) throw std::runtime_error("The selected Java installation has no JNI invocation entry point");
        std::vector<std::string> values = {"-Dfile.encoding=UTF-8"};
        appendOptions(values, L"JAVA_OPTS");
        appendOptions(values, L"SCOUTERCTL_OPTS");
        values.push_back("-Djava.class.path=" + classPath());
        std::vector<JavaVMOption> options;
        for (auto& value : values) options.push_back({value.data(), nullptr});
        JavaVMInitArgs init{};
        init.version = JNI_VERSION_21;
        init.nOptions = static_cast<jint>(options.size());
        init.options = options.data();
        JavaVM* vm = nullptr;
        JNIEnv* env = nullptr;
        const jint status = create(&vm, reinterpret_cast<void**>(&env), &init);
        if (status == JNI_EVERSION) throw std::runtime_error("scouterctl requires Java 21 or newer");
        if (status != JNI_OK) throw std::runtime_error("Could not initialize Java; check the configured JVM options");
        return invokeMain(vm, env, argc, argv);
    } catch (const std::exception& error) {
        std::fprintf(stderr, "scouterctl: %s\n", error.what());
        return 1;
    }
}
