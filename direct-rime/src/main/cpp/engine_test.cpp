#include "engine.h"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <new>
#include <stdexcept>
#include <thread>
#include <filesystem>
#include <fstream>
#include <cstdlib>
#include <map>
#include <random>

using namespace direct_rime;
#define CHECK(condition) do { if (!(condition)) throw std::runtime_error(#condition); } while (0)
namespace {
class TemporaryDirectory {
public:
    TemporaryDirectory() {
        const auto parent = std::filesystem::canonical(std::filesystem::temp_directory_path());
        std::random_device random;
        for (int attempt = 0; attempt < 32; ++attempt) {
            const auto candidate = parent / ("direct-rime-deploy-" + std::to_string(random()) + "-" + std::to_string(random()));
            std::error_code error;
            // An existing file, directory or link is never reused or removed.
            if (std::filesystem::create_directory(candidate, error)) { path_ = candidate; return; }
            if (error && error != std::errc::file_exists)
                throw std::filesystem::filesystem_error("create private test directory", candidate, error);
        }
        throw std::runtime_error("cannot allocate a private test directory");
    }
    TemporaryDirectory(const TemporaryDirectory&) = delete;
    TemporaryDirectory& operator=(const TemporaryDirectory&) = delete;
    ~TemporaryDirectory() {
        std::error_code error;
        // path_ was assigned only after this scope atomically created the directory.
        if (!path_.empty()) std::filesystem::remove_all(path_, error);
    }
    const std::filesystem::path& path() const { return path_; }
private:
    std::filesystem::path path_;
};

struct Fake {
    bool maintenance = false, exists = true, hasSchema = true, failDeploy = false, failLaunch = false, noLearning = false;
    bool ascii = false, fixSchemaListOrder = false;
    // Mirrors default.yaml's switcher/save_options and the user config it writes.
    std::vector<std::string> saveOptions{"ascii_punct", "s2t"};
    std::map<std::string, bool> savedOptions;
    std::map<std::string, bool> inputTypeOptions;
    int userConfigOpens = 0;
    int keys = 0, mask = 0, created = 0, destroyed = 0, freedCommit = 0;
    int setups = 0, initialized = 0, finalized = 0, selected = -1, freedTabs = 0, freedCodes = 0;
    int logLevel = -1;
    int statusReads = 0, freedStatus = 0, statusFailures = 0;
    int contextNotifications = 0, tabsNotifications = 0, statusNotifications = 0;
    size_t selectedStart = 0, selectedSpan = 0, selectedGlobal = 999, deletedGlobal = 999, caret = 999, tabSpan = 3;
    int freedIterator = 0, cleared = 0;
    bool rejectCodeChoice = false;
    std::string commit, selectedLabel, preedit, clientApp, appName, distribution, version, tabLabel = "ni";
    std::string selectedReplacement, tabReplacement = "ni\'";
    size_t tabStart = 1;
    std::string schema = "test", schemaName = "Test";
    // Ordered regardless of schema_list order: user.yaml restores the previously selected one.
    std::vector<std::pair<std::string, std::string>> schemas{{"test", "Test"}};
    std::vector<std::pair<std::string, std::string>> inputOptions;
    std::string rawInput;
    size_t rawCaret = 0;
    bool syncLaunch = true, syncPending = false, syncThrows = false, userDbHeld = false, failStartup = false;
    int syncCalls = 0, rawReads = 0;
    std::vector<std::string> syncMessages = {"start", "success"};
    RimeNotificationHandler callback = nullptr;
    void* context = nullptr;
    // Native candidate edit fixture.
    size_t editIndex = 999;
    bool editPage = false;
    int editCalls = 0;
    std::string editAction;
    std::map<std::string, std::string> schemaValues;
    std::string initialSchema;
    bool hasDeploymentResult = false, otherTaskFailed = false;
    std::vector<std::string> deployed, failed;
    int warmCalls = 0, freedWarm = 0, releasedWarm = 0;
    std::vector<std::string> warmPaths;
} f;
void notifyOptionIfRequested(int& count) {
    if (count <= 0) return;
    --count;
    if (f.callback) f.callback(f.context, 7, "option", "test");
}
RimeApi api() {
    RimeApi a{}; RIME_STRUCT_INIT(RimeApi, a);
    a.setup = [](RimeTraits* traits) { ++f.setups; f.logLevel = traits->min_log_level; f.appName = traits->app_name; };
    a.initialize = [](RimeTraits* traits) {
        ++f.initialized; f.distribution = traits->distribution_code_name; f.version = traits->distribution_version;
    };
    a.finalize = [] { ++f.finalized; f.userDbHeld = false; };
    a.set_notification_handler = [](RimeNotificationHandler callback, void* context) { f.callback = callback; f.context = context; };
    a.start_maintenance = [](Bool full) -> Bool {
        if (!full && f.failStartup) {
            if (f.callback) f.callback(f.context, 0, "deploy", "failure");
            return true;
        }
        if (full && f.failLaunch) return false;
        if (full && f.callback) f.callback(f.context, 0, "deploy", f.failDeploy ? "failure" : "success");
        return full;
    };
    a.is_maintenance_mode = []() -> Bool { return f.maintenance; };
    a.join_maintenance_thread = [] {};
    a.create_session = []() -> RimeSessionId {
        ++f.created; f.preedit.clear(); f.inputTypeOptions.clear();
        return 7;
    };
    a.find_session = [](RimeSessionId) -> Bool { return f.exists; };
    a.destroy_session = [](RimeSessionId) -> Bool { ++f.destroyed; return true; };
    a.set_option = [](RimeSessionId, const char* key, Bool value) {
        if (!std::strcmp(key, "_no_learning")) f.noLearning = value;
        else {
            if (!std::strcmp(key, "ascii_mode")) f.ascii = value;
            if (f.callback) f.callback(f.context, 7, "option", key);
        }
    };
    a.get_option = [](RimeSessionId, const char* key) -> Bool {
        if (!std::strcmp(key, "_no_learning")) return f.noLearning;
        if (!std::strcmp(key, "ascii_mode")) return f.ascii;
        const auto type = f.inputTypeOptions.find(key);
        return type != f.inputTypeOptions.end() && type->second;
    };
    a.set_property = [](RimeSessionId, const char* key, const char* value) { if (!std::strcmp(key, "client_app")) f.clientApp = value; };
    a.process_key = [](RimeSessionId, int, int mask) -> Bool { ++f.keys; f.mask = mask; f.preedit = "ni"; return false; };
    a.get_input = [](RimeSessionId) -> const char* { ++f.rawReads; return f.rawInput.c_str(); };
    a.get_commit = [](RimeSessionId, RimeCommit* c) -> Bool {
        if (f.commit.empty()) return false;
        c->text = f.commit.data(); return true;
    };
    a.free_commit = [](RimeCommit*) -> Bool { ++f.freedCommit; f.commit.clear(); return true; };
    a.get_context = [](RimeSessionId, RimeContext* c) -> Bool {
        notifyOptionIfRequested(f.contextNotifications);
        static RimeCandidate candidates[] = {{const_cast<char*>("你😀"), const_cast<char*>("注释"), nullptr}};
        c->composition.preedit = f.preedit.data(); c->composition.cursor_pos = static_cast<int>(f.preedit.size());
        c->menu.candidates = candidates; c->menu.num_candidates = f.preedit.empty() ? 0 : 1; c->menu.page_size = 3;
        c->menu.page_no = 1; c->menu.select_keys = const_cast<char*>("abc"); return true;
    };
    a.free_context = [](RimeContext*) -> Bool { return true; };
    a.get_status = [](RimeSessionId, RimeStatus* s) -> Bool {
        ++f.statusReads;
        notifyOptionIfRequested(f.statusNotifications);
        if (f.statusFailures > 0) { --f.statusFailures; return false; }
        s->schema_id = f.schema.data(); s->schema_name = f.schemaName.data(); s->is_ascii_mode = f.ascii; return true;
    };
    a.free_status = [](RimeStatus*) -> Bool { ++f.freedStatus; return true; };
    a.clear_composition = [](RimeSessionId) { ++f.cleared; f.preedit.clear(); };
    a.select_candidate_on_current_page = [](RimeSessionId, size_t i) -> Bool { f.selected = static_cast<int>(i); return true; };
    a.select_candidate = [](RimeSessionId, size_t i) -> Bool { f.selectedGlobal = i; return true; };
    a.delete_candidate = [](RimeSessionId, size_t i) -> Bool { f.deletedGlobal = i; return true; };
    a.set_caret_pos = [](RimeSessionId, size_t i) { f.caret = i; };
    a.candidate_list_from_index = [](RimeSessionId, RimeCandidateListIterator* iter, int offset) -> Bool {
        iter->index = offset - 1; return true;
    };
    a.candidate_list_next = [](RimeCandidateListIterator* iter) -> Bool {
        if (++iter->index >= 4) return false;
        iter->candidate.text = const_cast<char*>("词😀"); iter->candidate.comment = const_cast<char*>("注释"); return true;
    };
    a.candidate_list_end = [](RimeCandidateListIterator*) { ++f.freedIterator; };
    a.select_schema = [](RimeSessionId, const char* schema) -> Bool {
        f.schema = schema;
        if (f.callback) f.callback(f.context, 7, "schema", schema);
        return true;
    };
    a.config_open = [](const char* config_id, RimeConfig* config) -> Bool {
        if (std::strcmp(config_id, "default")) return false;
        config->ptr = &f; return true;
    };
    a.user_config_open = [](const char* config_id, RimeConfig* config) -> Bool {
        if (std::strcmp(config_id, "user")) return false;
        ++f.userConfigOpens; config->ptr = &f; return true;
    };
    a.config_close = [](RimeConfig*) -> Bool { return true; };
    a.schema_open = [](const char*, RimeConfig* config) -> Bool { config->ptr = &f; return true; };
    a.config_get_bool = [](RimeConfig*, const char* key, Bool* value) -> Bool {
        if (std::strcmp(key, "switcher/fix_schema_list_order")) return false;
        *value = f.fixSchemaListOrder; return true;
    };
    a.config_list_size = [](RimeConfig*, const char* key) -> size_t {
        const std::string name(key);
        if (name == "switcher/save_options") return f.saveOptions.size();
        return 0;
    };
    a.config_get_cstring = [](RimeConfig*, const char* key) -> const char* {
        const std::string prefix = "switcher/save_options/@";
        const std::string name(key);
        if (name.compare(0, prefix.size(), prefix)) return nullptr;
        const auto index = std::strtoul(name.c_str() + prefix.size(), nullptr, 10);
        return index < f.saveOptions.size() ? f.saveOptions[index].c_str() : nullptr;
    };
    a.config_set_bool = [](RimeConfig*, const char* key, Bool value) -> Bool {
        f.savedOptions[key] = value; return true;
    };
    a.get_schema_list = [](RimeSchemaList* list) -> Bool {
        static std::vector<std::string> ids, names;
        static std::vector<RimeSchemaListItem> items;
        ids.clear(); names.clear(); items.clear();
        for (const auto& entry : f.schemas) { ids.push_back(entry.first); names.push_back(entry.second); }
        items.reserve(ids.size());
        for (size_t i = 0; i < ids.size(); ++i) items.push_back({ids[i].data(), names[i].data(), nullptr});
        list->list = items.data(); list->size = f.hasSchema ? items.size() : 0; return true;
    };
    a.free_schema_list = [](RimeSchemaList*) {};
#ifdef RIME_SCHEMA_PREWARM
    a.get_initial_schema = [](char* schema, size_t size) -> Bool {
        if (f.initialSchema.empty() && (!f.hasSchema || f.schemas.empty())) return false;
        const auto& id = f.initialSchema.empty() ? f.schemas.front().first : f.initialSchema;
        std::snprintf(schema, size, "%s", id.c_str());
        return true;
    };
    a.get_deployment_result = [](RimeDeploymentResult* result) -> Bool {
        if (!f.hasDeploymentResult) return false;
        static std::vector<char*> good, bad, all;
        good.clear(); bad.clear(); all.clear();
        for (auto& id : f.deployed) good.push_back(id.data());
        for (auto& id : f.failed) bad.push_back(id.data());
        all = good; all.insert(all.end(), bad.begin(), bad.end());
        result->schema_ids = {all.size(), all.data()};
        result->successful_schemas = {good.size(), good.data()};
        result->failed_schemas = {bad.size(), bad.data()};
        result->success = bad.empty();
        return true;
    };
    a.free_deployment_result = [](RimeDeploymentResult*) {};
#ifdef RIME_DEPLOYMENT_FAILURE_SCOPE
    a.deployment_has_other_failure = []() -> Bool { return f.otherTaskFailed; };
#endif
    a.prewarm_schema = [](const char* schema, RimeStringList* result) -> Bool {
        CHECK(schema == nullptr);
        ++f.warmCalls;
        static std::vector<char*> paths;
        paths.clear();
        for (auto& path : f.warmPaths) paths.push_back(path.data());
        *result = {paths.size(), paths.data()};
        return true;
    };
    a.free_string_list = [](RimeStringList*) { ++f.freedWarm; };
    a.release_prewarm = [] { ++f.releasedWarm; };
#endif
#ifdef RIME_EVO_TYPE_GROUPS
    a.get_type_option = [](RimeSessionId, const char* schema, const char* group, char* option, size_t size) -> Bool {
        if (std::strcmp(schema, "test") || std::strcmp(group, "input_types")) return false;
        const auto selected = f.schemaValues.find("type_choice");
        const auto& choice = selected == f.schemaValues.end() ? f.inputOptions.front().first : selected->second;
        if (choice.size() >= size) return false;
        std::snprintf(option, size, "%s", choice.c_str()); return true;
    };
    a.select_type_option = [](RimeSessionId, const char* schema, const char* group, const char* option) -> Bool {
        if (std::strcmp(schema, "test") || std::strcmp(group, "input_types")) return false;
        if (std::none_of(f.inputOptions.begin(), f.inputOptions.end(),
                         [&](const auto& item) { return item.first == option; })) return false;
        f.schemaValues["type_choice"] = option;
        return true;
    };
#endif
#ifdef RIME_EVO_TYPE_OPTIONS
    a.get_type_options = [](RimeSessionId, const char* schema, const char* group, RimeTypeOption** items, size_t* count) -> Bool {
        if (std::strcmp(schema, "test") || std::strcmp(group, "input_types") || f.inputOptions.empty()) return false;
        static std::vector<RimeTypeOption> values;
        values.clear();
        const auto selected = f.schemaValues.find("type_choice");
        const auto& choice = selected == f.schemaValues.end() ? f.inputOptions.front().first : selected->second;
        for (auto& item : f.inputOptions)
            values.push_back({item.first.data(), item.first.data(), item.first == choice});
        *items = values.data(); *count = values.size(); return true;
    };
    a.free_type_options = [](RimeTypeOption*, size_t) {};
#endif
#ifdef RIME_EVO_CODE_CHOICES
    a.get_code_choices = [](RimeSessionId, RimeCodeChoice** choices, size_t* count) -> Bool {
        notifyOptionIfRequested(f.tabsNotifications);
        static RimeCodeChoice items[3];
        items[0] = {0, 1, const_cast<char*>(""), const_cast<char*>("x")};
        items[1] = {0, 2, const_cast<char*>("\x81"), const_cast<char*>("x")};
        items[2] = {f.tabStart, f.tabStart + f.tabSpan, f.tabLabel.data(), f.tabReplacement.data()};
        *choices = items; *count = f.preedit.empty() ? 0 : 3; return true;
    };
    a.free_code_choices = [](RimeCodeChoice*, size_t) { ++f.freedTabs; };
    a.select_code_choice = [](RimeSessionId, size_t start, size_t end, const char* replacement) -> Bool {
        if (f.rejectCodeChoice) return false;
        f.selectedStart = start; f.selectedSpan = end - start; f.selectedReplacement = replacement; return true;
    };
#endif
    return a;
}
void testInputContract() {
    f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
    auto initial = engine.begin(1, "editor.one"); CHECK(initial.gate == Ready);
    f.commit = "你😀";
    auto result = engine.key(1, 'n', 0xffffffff, true);
    CHECK(!result.handled && result.gate == Ready && result.commit == "你😀");
    CHECK(f.keys == 1 && f.mask == (1 | 2 | 4 | 8 | 64 | (1 << 26) | (1 << 30)));
    CHECK(f.freedCommit == 1 && result.candidates[0] == "你😀" && f.freedCodes == 0);
    CHECK(result.labels[0] == "a ");
#ifdef RIME_EVO_CODE_CHOICES
    CHECK(result.tabs.size() == 1 && result.tabs[0] == "ni" && f.freedTabs > 0);
#else
    CHECK(result.tabs.empty() && f.freedTabs == 0);
#endif
    CHECK(result.optionsRevision == initial.optionsRevision);
    auto selected = engine.action(1, result.revision, 4, 0, "");
#ifdef RIME_EVO_CODE_CHOICES
    CHECK(f.selectedStart == 1 && f.selectedReplacement == "ni'" && f.selectedSpan == 3 && selected.commit.empty() && selected.handled);
    f.rejectCodeChoice = true;
    selected = engine.action(1, selected.revision, 4, 0, "");
    CHECK(selected.gate == Ready && !selected.handled && selected.commit.empty());
    f.rejectCodeChoice = false;
#else
    CHECK(f.selectedSpan == 0 && selected.commit.empty() && !selected.handled);
#endif
    selected = engine.action(1, selected.revision, 4, -1, "");
    CHECK(selected.gate == Ready && !selected.handled);
    selected = engine.action(1, selected.revision, 4, 999, "");
    CHECK(selected.gate == Ready && !selected.handled);
    selected = engine.action(1, selected.revision, 7, -1, "");
    CHECK(selected.gate == Ready && !selected.handled && selected.commit.empty());
    CHECK(engine.action(1, result.revision, 1, 0, "").gate == Stale && f.selected == -1);
    auto candidateSelected = engine.action(1, selected.revision, 1, 0, ""); CHECK(f.selected == 0);
    auto unchangedTabs = engine.key(1, 'x', 0, true);
    CHECK(unchangedTabs.tabsRevision == candidateSelected.tabsRevision);
#ifdef RIME_EVO_CODE_CHOICES
    f.tabSpan = 4;
    auto changedTabs = engine.key(1, 'x', 0, true);
    CHECK(changedTabs.tabs[0] == "ni" && changedTabs.tabsRevision > unchangedTabs.tabsRevision);
    engine.action(1, changedTabs.revision, 4, 0, ""); CHECK(f.selectedSpan == 4);
    f.tabStart = 3;
    f.tabReplacement = "mi'";
    auto moved = engine.key(1, 'x', 0, true);
    CHECK(moved.tabsRevision > changedTabs.tabsRevision);
    engine.action(1, moved.revision, 4, 0, "");
    CHECK(f.selectedStart == 3 && f.selectedReplacement == "mi'");
#endif
    const int processedKeys = f.keys;
    engine.begin(2, "editor.two"); CHECK(f.destroyed == 1);
    f.commit = "must not drain";
    CHECK(engine.key(1, 'a', 0, false).gate == Stale && f.keys == processedKeys && f.commit == "must not drain");
    engine.end(1); CHECK(f.destroyed == 1);
    f.maintenance = true;
    auto gated = engine.key(2, 'a', 0, true); CHECK(gated.gate == Maintenance && gated.handled && f.keys == processedKeys);
    f.maintenance = false; f.exists = false;
    CHECK(engine.key(2, 'a', 0, false).gate == NoSession && f.keys == processedKeys);
    bool wrongThreadRejected = false;
    std::thread thread([&] { try { engine.key(2, 'a', 0, false); } catch (const std::logic_error&) { wrongThreadRejected = true; } });
    thread.join(); CHECK(wrongThreadRejected);
    engine.stop(); engine.stop(); CHECK(f.finalized == 1);
    engine.start("shared", "user"); CHECK(f.setups == 1 && f.logLevel == 2); engine.stop();
}
void testEngineLogLevel() {
    f = {}; auto a = api(); Engine engine(&a);
    engine.start("shared", "user", "development", false, 0);
    CHECK(f.logLevel == 0);
    engine.stop();
    // setup() runs once per process, so a later start keeps the level it already applied.
    engine.start("shared", "user", "development", false, 3);
    CHECK(f.setups == 1 && f.logLevel == 0);
    engine.stop();
    f = {}; auto b = api(); Engine clamped(&b);
    clamped.start("shared", "user", "development", false, 99);
    CHECK(f.setups == 1 && f.logLevel == 3);
    clamped.stop();
}
void testMaintenanceAndAbi() {
    f = {}; auto a = api();
    // A shorter historical API has none of the custom ABI. Never dereference beyond it.
#ifdef RIME_EVO_CODE_CHOICES
    a.data_size = offsetof(RimeApi, get_code_choices) - sizeof(a.data_size);
#else
    a.data_size = offsetof(RimeApi, change_page) - sizeof(a.data_size);
#endif
    Engine engine(&a); engine.start("shared", "user", "1.2.3-test");
    CHECK(f.appName == "rime.wanxiang" && f.distribution == "wanxiang-ime" && f.version == "1.2.3-test");
    auto result = engine.begin(1, "editor"); CHECK(result.tabs.empty() && f.freedTabs == 0);
    f.failDeploy = true; CHECK(!engine.deploy());
    CHECK(engine.begin(2, "editor").gate == DeployFailed);
    CHECK(engine.key(2, 'a', 0, false).handled && f.keys == 0);
    f.failDeploy = false; CHECK(engine.deploy());
    CHECK(f.appName == "rime.wanxiang" && f.distribution == "wanxiang-ime" && f.version == "1.2.3-test");
    CHECK(engine.begin(3, "editor").gate == Ready);
    CHECK(f.setups == 1 && f.initialized == 3 && f.finalized == 2);
    f.failLaunch = true; CHECK(!engine.deploy());
    CHECK(engine.begin(4, "editor").gate == DeployFailed);
    f.failLaunch = false; CHECK(engine.deploy());
    CHECK(engine.begin(5, "editor").gate == Ready);
    CHECK(f.setups == 1 && f.initialized == 5 && f.finalized == 4);
    engine.stop();
    f = {}; f.hasSchema = false; auto b = api(); Engine empty(&b); empty.start("shared", "user");
    CHECK(empty.begin(1, "editor").gate == NoSession && f.created == 0); empty.stop();
}
void testFrontendExtensions() {
    f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
    auto value = engine.begin(1, "editor");
    CHECK(engine.schemaEntries() == std::vector<std::string>({"test", "Test"}));
    CHECK(engine.schemaSelection().empty()); // Levers is optional, including on a shorter API.
    CHECK(engine.currentSchema() == "test"); // No get_current_schema: fall back to the first schema list entry.
    auto words = engine.candidates(1, 1, 10);
    CHECK(words.size() == 6 && words[0] == "词😀" && words[1] == "注释" && f.freedIterator == 1);
    CHECK(engine.candidates(1, -1, 10).empty() && engine.candidates(1, 0, 257).empty());
    value = engine.action(1, value.revision, 11, 3, ""); CHECK(f.selectedGlobal == 3);
    value = engine.action(1, value.revision, 12, 2, ""); CHECK(f.deletedGlobal == 2);
    value = engine.action(1, value.revision, 9, 1, ""); CHECK(f.caret == 1);
    engine.begin(2, "second");
    CHECK(engine.candidates(1, 0, 10).empty() && f.freedIterator == 1);
    engine.stop();
}
void testCandidateSequence() {
    f = {}; auto a = api();
#ifdef RIME_CANDIDATE_EDIT_ACTIONS
    a.edit_candidate = [](RimeSessionId, size_t index, Bool page, const char* action) -> Bool {
        ++f.editCalls;
        f.editIndex = index; f.editPage = page; f.editAction = action;
        return f.editAction != "bogus";
    };
#endif
    Engine engine(&a); engine.start("shared", "user");
    auto value = engine.begin(1, "editor");
#ifdef RIME_CANDIDATE_EDIT_ACTIONS
    CHECK(engine.candidateEditAvailable());
    value = engine.action(1, value.revision, 14, 2, "manual_up");
    CHECK(value.handled && f.editCalls == 1 && f.editIndex == 2 && f.editPage && f.editAction == "manual_up");
    value = engine.action(1, value.revision, 15, 7, "manual_down");
    CHECK(value.handled && f.editCalls == 2 && f.editIndex == 7 && !f.editPage && f.editAction == "manual_down");
    CHECK(engine.action(1, value.revision - 1, 14, 0, "auto_delete").gate == Stale && f.editCalls == 2);
    value = engine.action(1, value.revision, 14, -1, "manual_delete");
    CHECK(!value.handled && f.editCalls == 2);
    value = engine.action(1, value.revision, 14, 0, "bogus");
    CHECK(!value.handled && f.editCalls == 3);
    CHECK(f.keys == 0);
    a.edit_candidate = nullptr;
#endif
    CHECK(!engine.candidateEditAvailable());
    value = engine.action(1, value.revision, 15, 2, "auto_delete");
    CHECK(value.handled && f.deletedGlobal == 2);
    value = engine.action(1, value.revision, 15, 2, "manual_up");
    CHECK(!value.handled && f.keys == 0);
    engine.stop();
}
void testReadOnlyRefresh() {
    f = {}; auto a = api();
    a.get_input = [](RimeSessionId) -> const char* { return f.rawInput.c_str(); };
    a.get_caret_pos = [](RimeSessionId) -> size_t { return f.rawCaret; };
    Engine engine(&a); engine.start("shared", "user");
    auto initial = engine.begin(1, "editor");
    f.preedit = "ni hao"; f.rawInput = "nihao"; f.rawCaret = 3; f.commit = "once";
    auto value = engine.action(1, initial.revision, 13, 6, "");
    CHECK(!value.handled && value.commit == "once" && f.freedCommit == 1);
    CHECK(f.caret == 999 && f.keys == 0 && f.rawCaret == 3);
    auto raw = engine.rawInput(1, value.revision);
    CHECK(raw && raw->text == "nihao" && raw->caret == 3);
    CHECK(!engine.rawInput(2, value.revision) && !engine.rawInput(1, value.revision - 1));
    f.rawCaret = 999; CHECK(!engine.rawInput(1, value.revision)); f.rawCaret = 3;
    // Reading raw coordinates neither consumes commits nor advances the snapshot revision.
    auto next = engine.action(1, value.revision, 13, 0, "");
    CHECK(next.gate == Ready && next.commit.empty() && f.freedCommit == 1 && f.caret == 999);
    a.get_input = nullptr; CHECK(!engine.rawInput(1, next.revision));
    engine.stop();
}
void testOptionalInputStatistics() {
    f = {}; auto a = api();
    // The operation consumes its input. Statistics must describe the state BEFORE it.
    a.process_key = [](RimeSessionId, int, int mask) -> Bool {
        ++f.keys; f.mask = mask; f.rawInput.clear(); return false;
    };
    a.select_candidate_on_current_page = [](RimeSessionId, size_t index) -> Bool {
        f.selected = static_cast<int>(index); f.rawInput.clear(); f.commit = "你"; return true;
    };
    a.select_candidate = [](RimeSessionId, size_t index) -> Bool {
        f.selectedGlobal = index; f.rawInput.clear(); f.commit = "好"; return true;
    };
    a.get_caret_pos = [](RimeSessionId) -> size_t { throw std::runtime_error("statistics must not read the caret"); };
    Engine engine(&a); engine.start("shared", "user");
    auto value = engine.begin(1, "editor");
    f.rawInput = "ni";
    value = engine.key(1, 'n', 0, false);
    CHECK(value.inputStatistics == -1 && f.rawReads == 0);
    value = engine.action(1, value.revision, 13, 0, "");
    CHECK(value.inputStatistics == -1 && f.rawReads == 0);

    f.rawInput = "汉é🙂"; // 4 UTF-16 code units, not 3 code points or 9 UTF-8 bytes.
    value = engine.key(1, 'x', 0, false, true);
    CHECK(value.inputStatistics == 8 && value.commit.empty() && f.rawReads == 1 && f.rawInput.empty());
    f.rawInput = "/abc"; f.commit = "命令结果";
    value = engine.key(1, 'x', 0, true, true);
    CHECK(value.inputStatistics == 9 && value.commit == "命令结果" && f.rawReads == 2);
    CHECK((f.mask & (1 << 30)) != 0 && f.freedCommit == 1);
    value = engine.key(1, 'x', 0, true);
    CHECK(value.inputStatistics == -1 && value.commit.empty() && f.rawReads == 2 && f.freedCommit == 1);

    f.rawInput = "pin";
    value = engine.action(1, value.revision, 1, 0, "", true);
    CHECK(value.inputStatistics == 6 && value.commit == "你" && f.selected == 0 && f.rawReads == 3);
    f.rawInput = "hao";
    value = engine.action(1, value.revision, 11, 2, "", true);
    CHECK(value.inputStatistics == 6 && value.commit == "好" && f.selectedGlobal == 2 && f.rawReads == 4);
    const auto reads = f.rawReads;
    CHECK(engine.action(1, value.revision - 1, 1, 0, "", true).inputStatistics == -1);
    CHECK(engine.key(0, 'x', 0, false, true).inputStatistics == -1);
    f.maintenance = true; CHECK(engine.key(1, 'x', 0, false, true).inputStatistics == -1); f.maintenance = false;
    CHECK(f.rawReads == reads);
    a.get_input = nullptr;
    CHECK(engine.key(1, 'x', 0, false, true).inputStatistics == -1 && f.rawReads == reads);
    a.get_input = [](RimeSessionId) -> const char* { throw std::runtime_error("optional observation failed"); };
    const auto keys = f.keys;
    CHECK(engine.key(1, 'x', 0, false, true).inputStatistics == -1 && f.keys == keys + 1);
    a.get_input = [](RimeSessionId) -> const char* { ++f.rawReads; return nullptr; };
    CHECK(engine.key(1, 'x', 0, false, true).inputStatistics == 0 && f.rawReads == reads + 1);
    engine.begin(2, "private-editor", true);
    CHECK(engine.key(2, 'x', 0, false, true).inputStatistics == -1 && f.rawReads == reads + 1);
    engine.stop();
}

void testInputStatisticsUnicodeAndPresentationReuse() {
    f = {}; auto a = api();
    a.process_key = [](RimeSessionId, int, int) -> Bool { ++f.keys; return false; };
    Engine engine(&a); engine.start("shared", "user");
    auto previous = engine.begin(1, "editor");
    const std::vector<std::pair<std::string, int64_t>> cases = {
        {"", 0}, {"n", 2}, {"/你好", 7}, {"汉é🙂", 8},
        {"\x80", 2}, {"/\xf0\x9f", 2}, {"\xc0\xaf", 2}, {"\xed\xa0\x80", 2}, {"\xf4\x8f\xbf\xbf", 4}
    };
    for (const auto& [text, expected] : cases) {
        f.rawInput = text;
        const auto current = engine.key(1, 'x', 0, true, true);
        CHECK(current.inputStatistics == expected && current.commit.empty());
        CHECK(current.canReusePresentation(previous));
        CHECK(current.revision > previous.revision);
        previous = current;
    }
    CHECK(f.rawReads == static_cast<int>(cases.size()));
    const auto disabled = engine.key(1, 'x', 0, true);
    CHECK(disabled.inputStatistics == -1 && disabled.canReusePresentation(previous));
    CHECK(f.rawReads == static_cast<int>(cases.size()));
    engine.stop();
}

void testSyncCompletion() {
    f = {}; auto a = api();
    a.sync_user_data = []() -> Bool {
        ++f.syncCalls;
        if (f.userDbHeld) return false; // Destroying a session does not run Lua GC.
        if (f.syncThrows) throw std::runtime_error("injected sync failure");
        f.syncPending = f.syncLaunch;
        return f.syncLaunch;
    };
    a.join_maintenance_thread = [] {
        if (!f.syncPending) return;
        std::thread worker([] {
            for (const auto& message : f.syncMessages)
                f.callback(f.context, 0, "deploy", message.c_str());
        });
        worker.join();
        f.syncPending = false;
    };
    Engine engine(&a); engine.start("shared", "user"); engine.begin(1, "editor");
    f.userDbHeld = true;
    CHECK(engine.sync());
    CHECK(f.setups == 1 && f.initialized == 2 && f.finalized == 1);
    CHECK(engine.key(1, 'n', 0, false).gate == NoSession && f.keys == 0);
    CHECK(engine.begin(2, "editor").gate == Ready && f.created == 2);
    f.syncMessages = {"start", "failure"};
    CHECK(!engine.sync()); CHECK(engine.begin(3, "editor").gate == Ready);
    f.syncMessages = {"start", "success"};
    CHECK(engine.sync()); CHECK(engine.begin(4, "editor").gate == Ready);
    f.syncMessages = {"start", "failure", "success"};
    CHECK(!engine.sync()); CHECK(engine.begin(5, "editor").gate == Ready);
    f.syncMessages = {"start"};
    CHECK(!engine.sync()); CHECK(engine.begin(6, "editor").gate == Ready);
    f.syncMessages = {"start", "success", "start"};
    CHECK(!engine.sync()); CHECK(engine.begin(7, "editor").gate == Ready);
    f.syncLaunch = false;
    f.callback(f.context, 0, "deploy", "success"); // An old completion is not this attempt's result.
    CHECK(!engine.sync()); CHECK(engine.begin(8, "editor").gate == Ready);
    f.syncLaunch = true; f.syncThrows = true;
    bool threw = false;
    try { engine.sync(); } catch (const std::runtime_error&) { threw = true; }
    CHECK(threw); CHECK(engine.begin(9, "editor").gate == Ready);
    f.syncThrows = false; f.syncMessages = {"start", "success"};
    CHECK(engine.sync()); CHECK(engine.begin(10, "editor").gate == Ready);
    f.failStartup = true;
    const int callsBeforeFailedStartup = f.syncCalls;
    CHECK(!engine.sync() && f.syncCalls == callsBeforeFailedStartup);
    CHECK(engine.begin(11, "editor").gate == DeployFailed);
    f.failStartup = false;
    CHECK(engine.sync()); CHECK(engine.begin(12, "editor").gate == Ready);
    // A real deployment failure is still classified as such after synchronization.
    f.failDeploy = true;
    CHECK(!engine.deploy()); CHECK(engine.begin(13, "editor").gate == DeployFailed);
    f.failDeploy = false;
    CHECK(engine.deploy()); CHECK(engine.begin(14, "editor").gate == Ready);
    const int calls = f.syncCalls;
    f.maintenance = true; CHECK(!engine.sync() && f.syncCalls == calls); f.maintenance = false;
    engine.stop(); CHECK(!engine.sync() && f.syncCalls == calls);
}
void testPresentationReuse() {
    Snapshot old;
    old.generation = 7; old.revision = 9; old.optionsRevision = 2; old.gate = Ready;
    old.commit = "previous commit"; old.preedit = "ni"; old.schema = "test"; old.schemaName = "Test";
    old.preview = "你"; old.candidates = {"你"}; old.comments = {""}; old.labels = {"1 "}; old.tabs = {"ni"};
    Snapshot current = old; current.commit.clear(); current.handled = false; ++current.revision;
    CHECK(current.canReusePresentation(old) && current.canCachePresentation());
    auto reject = [&](Snapshot changed) { CHECK(!changed.canReusePresentation(old)); };
    auto changed = current; changed.handled = true; reject(changed);
    changed = current; changed.commit = "new commit"; reject(changed);
    changed = current; ++changed.generation; reject(changed);
    changed = current; ++changed.optionsRevision; reject(changed);
    changed = current; changed.gate = Stale; reject(changed);
    changed = current; changed.ascii = true; reject(changed);
    changed = current; ++changed.page; reject(changed);
    changed = current; changed.lastPage = !changed.lastPage; reject(changed);
    changed = current; ++changed.cursor; reject(changed);
    changed = current; ++changed.highlighted; reject(changed);
    changed = current; changed.preedit += "x"; reject(changed);
    changed = current; changed.schema += "x"; reject(changed);
    changed = current; changed.schemaName += "x"; reject(changed);
    changed = current; changed.preview += "x"; reject(changed);
    changed = current; changed.candidates[0] += "x"; reject(changed);
    changed = current; changed.comments[0] += "x"; reject(changed);
    changed = current; changed.labels[0] += "x"; reject(changed);
    changed = current; changed.tabs[0] += "x"; reject(changed);
    Snapshot large; large.gate = Ready; large.preedit.assign(64 * 1024, 'x');
    CHECK(large.canCachePresentation());
    large.preedit.push_back('x'); CHECK(!large.canCachePresentation());
    large.preedit.clear(); large.commit.assign(64 * 1024 + 1, 'x'); CHECK(!large.canCachePresentation());
    large.commit.clear(); large.candidates.resize(1025); CHECK(!large.canCachePresentation());
    current.gate = NoSession; CHECK(!current.canCachePresentation());
}
void testUnicode() {
    std::u16string decoded;
    CHECK(utf16("你😀", decoded) && decoded.size() == 3);
    CHECK(!utf16("\xc0\xaf", decoded)); CHECK(!utf16("\xed\xa0\x80", decoded));
    CHECK(!utf16("\xf4\x90\x80\x80", decoded)); CHECK(!utf16("\xe4\xbd", decoded));
}
void testOptionInvalidation() {
    f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
    auto initial = engine.begin(1, "editor");
    CHECK(engine.key(1, 'n', 0, false).optionsRevision == initial.optionsRevision);
    f.callback(f.context, 7, "option", "ascii_mode");
    auto changed = engine.key(1, 'i', 0, false);
    CHECK(changed.optionsRevision > initial.optionsRevision);
    f.callback(f.context, 7, "schema", "second");
    CHECK(engine.key(1, 'i', 0, false).optionsRevision > changed.optionsRevision);
    CHECK(engine.begin(2, "second").optionsRevision > changed.optionsRevision);
    engine.stop();
}
void testStatusCache() {
    {
        f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
        auto initial = engine.begin(1, "editor");
        CHECK(initial.schema == "test" && initial.schemaName == "Test" && !initial.ascii);
        CHECK(f.statusReads == 1 && f.freedStatus == 1);
        f.tabSpan = 4;
        auto dynamic = engine.key(1, 'n', 0, false);
        CHECK(dynamic.revision > initial.revision && dynamic.preedit == "ni");
#ifdef RIME_EVO_CODE_CHOICES
        CHECK(dynamic.tabs[0] == "ni");
#endif
        CHECK(f.statusReads == 1); // Dynamic context/tabs never come from the status cache.

        f.schema = "second"; f.schemaName = "Second"; f.ascii = true;
        f.callback(f.context, 7, "schema", "second");
        auto changed = engine.key(1, 'i', 0, false);
        CHECK(changed.schema == "second" && changed.schemaName == "Second" && changed.ascii);
        CHECK(f.statusReads == 2);

        f.contextNotifications = 1;
        engine.key(1, 'x', 0, false); CHECK(f.statusReads == 3);
#ifdef RIME_EVO_CODE_CHOICES
        f.tabsNotifications = 1;
#else
        f.contextNotifications = 1;
#endif
        engine.key(1, 'x', 0, false); CHECK(f.statusReads == 4);

        f.callback(f.context, 7, "option", "late_once");
        f.statusNotifications = 1;
        f.commit = "once";
        auto retried = engine.key(1, 'x', 0, false);
        CHECK(f.statusReads == 6 && retried.commit == "once" && f.freedCommit == 1);
        engine.key(1, 'x', 0, false); CHECK(f.statusReads == 6); // Stable reread was cached.

        f.callback(f.context, 7, "option", "continuous");
        f.statusNotifications = 2;
        engine.key(1, 'x', 0, false); CHECK(f.statusReads == 8); // At most one reread.
        engine.key(1, 'x', 0, false); CHECK(f.statusReads == 9); // Unstable result was not cached.

        f.callback(f.context, 7, "option", "failure");
        f.statusFailures = 1;
        auto failed = engine.key(1, 'x', 0, false);
        CHECK(f.statusReads == 10 && failed.schema.empty() && failed.schemaName.empty() && !failed.ascii);
        auto recovered = engine.key(1, 'x', 0, false);
        CHECK(f.statusReads == 11 && recovered.schema == "second" && recovered.schemaName == "Second" && recovered.ascii);
        CHECK(f.freedStatus == f.statusReads - 1);
        engine.stop();
    }
    {
        f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
        engine.begin(1, "editor"); CHECK(f.statusReads == 1 && f.created == 1);
        engine.key(0, 'x', 0, false); // A stale request cannot evict the current editor's status.
        engine.key(1, 'x', 0, false); CHECK(f.statusReads == 1);
        f.maintenance = true;
        CHECK(engine.key(1, 'x', 0, false).gate == Maintenance);
        f.maintenance = false;
        engine.key(1, 'x', 0, false); CHECK(f.statusReads == 2);
        engine.begin(2, "editor"); CHECK(f.statusReads == 3 && f.created == 1); // Same native session, new generation.
        engine.end(2);
        engine.begin(3, "editor"); CHECK(f.statusReads == 4);
        CHECK(engine.deploy());
        engine.begin(4, "editor"); CHECK(f.statusReads == 5);
        engine.stop(); engine.stop();
        engine.start("shared", "user");
        engine.begin(5, "editor"); CHECK(f.statusReads == 6);
        engine.stop();
    }
    {
        f = {}; f.schema.assign(32 * 1024, 's'); f.schemaName.assign(32 * 1024, 'n');
        auto a = api(); Engine engine(&a); engine.start("shared", "user");
        auto exact = engine.begin(1, "editor");
        CHECK(exact.schema.size() == 32 * 1024 && exact.schemaName.size() == 32 * 1024);
        engine.key(1, 'x', 0, false); CHECK(f.statusReads == 1); // Exact budget is cacheable.
        f.schemaName.push_back('x'); f.callback(f.context, 7, "option", "oversize");
        auto large = engine.key(1, 'x', 0, false);
        CHECK(large.schemaName.size() == 32 * 1024 + 1 && f.statusReads == 2);
        engine.key(1, 'x', 0, false); CHECK(f.statusReads == 3); // Full fields returned, never cached.
        engine.stop();
    }
}
void testCandidateIteratorFailureDoesNotReclosePartialApiState() {
    // The pinned API frees the previous text/comment before copying the next
    // candidate. An exception from next() does not guarantee end() is safe.
    for (const int offset : {0, 1}) {
        f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
        engine.begin(1, "editor");
        a.candidate_list_next = [](RimeCandidateListIterator* iter) -> Bool {
            if (++iter->index > 0) throw std::bad_alloc();
            iter->candidate.text = const_cast<char*>("first");
            iter->candidate.comment = const_cast<char*>("");
            return true;
        };
        bool failed = false;
        try { engine.candidates(1, offset, 2); }
        catch (const std::bad_alloc&) { failed = true; }
        CHECK(failed);
        CHECK(f.freedIterator == 0);
        engine.stop();
    }
}
void testNativeSchemaSwitchSelection() {
#ifdef RIME_SCHEMA_SWITCH_SELECTION
    f = {}; auto a = api();
    // A capture-free mock mirrors the native preference contract.
    a.select_switch = [](RimeSessionId, const char* name, Bool value) -> Bool {
        if (std::strcmp(name, "ascii_mode") && std::strcmp(name, "s2t")) return false;
        if (!std::strcmp(name, "ascii_mode")) f.ascii = value;
        f.savedOptions[std::string("native/") + name] = value;
        return true;
    };
    Engine engine(&a); engine.start("shared", "user");
    auto value = engine.begin(1, "editor");
    value = engine.action(1, value.revision, 8, 1, "s2t");
    CHECK(f.savedOptions["native/s2t"] && f.userConfigOpens == 0);
    value = engine.action(1, value.revision, 6, 0, "");
    CHECK(value.ascii && f.savedOptions["native/ascii_mode"] && f.userConfigOpens == 0);
    value = engine.action(1, value.revision, 8, 1, "_no_learning");
    CHECK(f.noLearning && f.userConfigOpens == 0 && f.savedOptions.size() == 2);
    engine.stop();
#endif
}
void testFrontendWritesOnlySaveableOptions() {
    f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
    auto value = engine.begin(1, "editor.one");
    // A switch listed in switcher/save_options is written where librime restores it.
    value = engine.action(1, value.revision, 8, 1, "s2t");
    CHECK(f.savedOptions.size() == 1 && f.savedOptions["var/option/s2t"] && f.userConfigOpens == 1);
    value = engine.action(1, value.revision, 8, 0, "s2t");
    CHECK(f.savedOptions.size() == 1 && !f.savedOptions["var/option/s2t"] && f.userConfigOpens == 2);
    // Everything else stays a session-local option.
    value = engine.action(1, value.revision, 8, 1, "tone_hint");
    CHECK(f.savedOptions.size() == 1 && f.userConfigOpens == 2);
    value = engine.action(1, value.revision, 8, 1, "_no_learning");
    CHECK(f.savedOptions.size() == 1 && f.userConfigOpens == 2);
    // A deployment can change the list; the next toggle reads it again.
    f.saveOptions = {"tone_hint"}; engine.deploy(); value = engine.begin(2, "editor.two");
    value = engine.action(2, value.revision, 8, 1, "tone_hint");
    CHECK(f.savedOptions.size() == 2 && f.savedOptions["var/option/tone_hint"]);
    value = engine.action(2, value.revision, 8, 1, "s2t");
    CHECK(!f.savedOptions["var/option/s2t"] && f.userConfigOpens == 3);
    engine.stop();
}
void testAsciiToggleSharesSavedOptionRule() {
    f = {}; f.saveOptions = {"ascii_mode"};
    auto a = api(); Engine engine(&a); engine.start("shared", "user");
    auto value = engine.begin(1, "editor.one");
    value = engine.action(1, value.revision, 6, 0, "");
    CHECK(value.ascii && f.savedOptions.size() == 1);
    CHECK(f.savedOptions["var/option/ascii_mode"] && f.userConfigOpens == 1);
    // A stale menu request must neither toggle the current session nor save a preference.
    CHECK(engine.action(1, value.revision - 1, 6, 0, "").gate == Stale);
    CHECK(f.ascii && f.userConfigOpens == 1);
    value = engine.action(1, value.revision, 6, 0, "");
    CHECK(!value.ascii && !f.savedOptions["var/option/ascii_mode"] && f.userConfigOpens == 2);
    // Both frontend entry points write the same preference, in both directions.
    value = engine.action(1, value.revision, 8, 1, "ascii_mode");
    CHECK(value.ascii && f.savedOptions["var/option/ascii_mode"] && f.userConfigOpens == 3);
    value = engine.action(1, value.revision, 6, 0, "");
    CHECK(!value.ascii && !f.savedOptions["var/option/ascii_mode"] && f.userConfigOpens == 4);
    // Removing the option from the deployed list makes future toggles session-local.
    f.saveOptions.clear(); engine.deploy(); value = engine.begin(2, "editor.two");
    value = engine.action(2, value.revision, 6, 0, "");
    CHECK(value.ascii && !f.savedOptions["var/option/ascii_mode"] && f.userConfigOpens == 4);
    engine.stop();
}
void testSessionReuseAndEditorIsolation() {
    f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
    CHECK(engine.begin(1, "editor.one").gate == Ready && !f.noLearning);
    auto typed = engine.key(1, 'n', 0, false);
    CHECK(!typed.preedit.empty());
#ifdef RIME_EVO_CODE_CHOICES
    CHECK(!typed.tabs.empty());
#endif
    // A new field in the same app reuses only the native session, never old input.
    f.commit = "must not cross editors";
    auto rebound = engine.begin(2, "editor.one");
    CHECK(f.created == 1 && f.destroyed == 0 && !f.noLearning && f.clientApp == "editor.one");
    CHECK(rebound.preedit.empty() && rebound.candidates.empty() && rebound.tabs.empty() && rebound.commit.empty());
    CHECK(f.freedCommit == 1);
    CHECK(engine.key(1, 'i', 0, false).gate == Stale && f.keys == 1);
    CHECK(engine.action(1, typed.revision, 1, 0, "").gate == Stale && f.selected == -1);
    // Ending a field is idempotent and gates both key-down and key-up.
    engine.key(2, 'n', 0, false); f.commit = "unclaimed";
    CHECK(engine.end(2).gate == NoSession && f.noLearning && f.freedCommit == 2);
    const int clears = f.cleared;
    engine.end(2); CHECK(f.cleared == clears && f.destroyed == 0);
    CHECK(engine.key(2, 'i', 0, false).gate == NoSession && f.keys == 2);
    CHECK(engine.key(2, 'i', 0, true).gate == NoSession && f.keys == 2);
    CHECK(engine.begin(2, "editor.one").gate == Stale);
    // Returning from a non-editor restores the next field's learning policy.
    CHECK(engine.begin(3, "editor.one", false).gate == Ready && !f.noLearning);
    engine.end(2); CHECK(engine.key(3, 'n', 0, false).gate == Ready);
    CHECK(engine.sessionStats() == std::vector<int64_t>({1, 2, 1}));
    // Sensitive boundaries reconstruct Lua components as well as native input.
    CHECK(engine.begin(4, "editor.one", true).gate == Ready && f.noLearning && f.created == 2);
    CHECK(engine.begin(5, "editor.one", false).gate == Ready && !f.noLearning && f.created == 3);
    // Cross-app and anonymous callers get a fresh native session.
    CHECK(engine.begin(6, "editor.two").gate == Ready && f.created == 4 && f.destroyed == 3);
    CHECK(engine.begin(7, "").gate == Ready && f.created == 5);
    CHECK(engine.begin(8, "").gate == Ready && f.created == 6);
    // An expired native session is recreated; deployment/stop cannot retain it.
    f.exists = false; engine.begin(9, "editor.two"); f.exists = true;
    CHECK(engine.begin(10, "editor.two").gate == Ready);
    CHECK(engine.deploy()); CHECK(engine.begin(11, "editor.two").gate == Ready && f.created == 8);
    engine.stop(); engine.start("shared", "user");
    CHECK(engine.begin(12, "editor.two").gate == Ready && f.created == 9);
    engine.stop();
}
void testGeneratedConfigInvalidation() {
    namespace fs = std::filesystem;
    const TemporaryDirectory temporary;
    const auto root = temporary.path();
    const fs::path user = root / "user", shared = root / "shared", build = user / "build";
    fs::create_directories(build); fs::create_directories(shared);
    auto write = [](const fs::path& path) { std::ofstream(path) << "keep data"; };
    write(shared / "default.yaml"); write(build / "default.yaml");
    write(user / "test.schema.yaml"); write(build / "test.schema.yaml");
    write(build / "compiled-only.schema.yaml"); write(build / "test.table.bin");
    write(user / "user.yaml"); fs::create_directory(user / "test.userdb"); write(user / "test.userdb/CURRENT");
    write(root / "target.yaml"); write(shared / "linked.yaml");
    fs::create_symlink(root / "target.yaml", build / "linked.yaml");
    f = {}; auto a = api(); Engine engine(&a); engine.start(shared.string(), user.string());
    CHECK(fs::exists(build / "default.yaml")); // Ordinary startup keeps warm caches.
    CHECK(engine.deploy());
    CHECK(!fs::exists(build / "default.yaml") && !fs::exists(build / "test.schema.yaml"));
    CHECK(!fs::exists(build / "linked.yaml") && fs::exists(root / "target.yaml"));
    CHECK(fs::exists(build / "compiled-only.schema.yaml") && fs::exists(build / "test.table.bin"));
    CHECK(fs::exists(user / "user.yaml") && fs::exists(user / "test.userdb/CURRENT"));
    engine.stop(); CHECK(engine.deploy()); engine.stop();
}
void testUsableDeploymentWarning() {
#if defined(RIME_SCHEMA_PREWARM) && defined(RIME_DEPLOYMENT_FAILURE_SCOPE)
    f = {}; auto a = api(); Engine engine(&a);
    f.failDeploy = true; f.hasDeploymentResult = true;
    f.initialSchema = "test"; f.deployed = {"test"}; f.failed = {"other"};
    engine.start("shared", "user", "development", true);
    CHECK(engine.deploymentDegraded());
    CHECK(engine.begin(1, "editor").gate == Ready);
    CHECK(engine.deploy() && engine.deploymentDegraded());
    engine.stop();
    auto rejected = [](bool known, const std::string& initial,
                       const std::vector<std::string>& good,
                       const std::vector<std::string>& bad, bool otherTaskFailed = false) {
        f = {}; auto a = api(); Engine engine(&a);
        f.failDeploy = true; f.hasDeploymentResult = known;
        f.initialSchema = initial; f.deployed = good; f.failed = bad; f.otherTaskFailed = otherTaskFailed;
        try { engine.start("shared", "user", "development", true); }
        catch (const std::runtime_error&) { return true; }
        engine.stop(); return false;
    };
    CHECK(rejected(false, "test", {"test"}, {"other"}));
    CHECK(rejected(true, "test", {"other"}, {"test"}));
    CHECK(rejected(true, "test", {"test"}, {})); // global failure
    CHECK(rejected(true, "test", {"test"}, {"other"}, true)); // mixed failure
    CHECK(rejected(true, "missing", {"test"}, {"other"}));
#endif
    // Old/short APIs never infer success from compiled filenames.
    f = {}; auto legacy = api(); Engine legacyEngine(&legacy);
#ifdef RIME_SCHEMA_PREWARM
    legacy.get_deployment_result = nullptr;
#endif
    f.failDeploy = true;
    bool legacyRejected = false;
    try { legacyEngine.start("shared", "user", "development", true); }
    catch (const std::runtime_error&) { legacyRejected = true; }
    CHECK(legacyRejected);
}

void testNativePrewarm() {
    f = {}; auto a = api(); Engine engine(&a);
    CHECK(engine.prewarmAssets().empty());
    engine.start("shared", "user");
#ifdef RIME_SCHEMA_PREWARM
    f.initialSchema = "native-choice";
    f.warmPaths = {"/user/build/dict.table.bin", "/shared/model.dlm"};
    CHECK(engine.currentSchema() == "native-choice");
    CHECK(engine.prewarmAssets() == f.warmPaths);
    CHECK(f.warmCalls == 1 && f.freedWarm == 1 && f.created == 0);
    engine.releasePrewarm();
    CHECK(f.releasedWarm == 1);
    a.prewarm_schema = nullptr;
#endif
    CHECK(engine.prewarmAssets().empty());
    engine.stop();
}
void testRuntimeTypeGroups() {
#ifdef RIME_EVO_TYPE_OPTIONS
    f = {};
    f.inputOptions = {{"zrm", ""}, {"pinyin", ""}};
    auto a = api(); Engine engine(&a); engine.start("shared", "user");
    CHECK((engine.typeGroupState("test", "input_types") ==
           std::vector<std::string>{"zrm", "zrm", "zrm", "pinyin", "pinyin"}));
    CHECK(engine.selectTypeOption("test", "input_types", "pinyin"));
    CHECK(engine.typeGroupState("test", "input_types").front() == "pinyin");
    CHECK(!engine.selectTypeOption("test", "input_types", "missing"));
    CHECK(f.created == 0);
    CHECK(f.userConfigOpens == 0);
    engine.stop();
#endif
}
void testCurrentSchemaPreference() {
    namespace fs = std::filesystem;
    const TemporaryDirectory temporary;
    const auto root = temporary.path();
    const fs::path user = root / "user";
    fs::create_directories(user);
    f = {}; f.schemas = {{"first", "First"}, {"second", "Second"}};
    auto a = api();
#ifdef RIME_SCHEMA_PREWARM
    a.get_initial_schema = nullptr;
#endif
    Engine engine(&a); engine.start("shared", user.string());
    CHECK(engine.currentSchema() == "first");
    std::ofstream(user / "user.yaml") << "var:\n  previously_selected_schema: second\n";
    CHECK(engine.currentSchema() == "second");
    // Fixed ordering makes librime ignore the stored preference for a new session.
    f.fixSchemaListOrder = true;
    CHECK(engine.currentSchema() == "first");
    f.fixSchemaListOrder = false;
    std::ofstream(user / "user.yaml") << "var:\n  previously_selected_schema: \"third\"\n";
    CHECK(engine.currentSchema() == "first");
    engine.stop();
}
}
int main(int argc, char** argv) {
    try {
        if (argc == 2 && std::strcmp(argv[1], "--statistics") == 0) {
            testOptionalInputStatistics(); testInputStatisticsUnicodeAndPresentationReuse();
            testReadOnlyRefresh(); testPresentationReuse(); testUnicode();
            std::cout << "input statistics contract: passed (5 groups)\n";
            return 0;
        }
        if (argc != 1) { std::cerr << "usage: engine-test [--statistics]\n"; return 2; }
        testInputContract(); testEngineLogLevel(); testMaintenanceAndAbi(); testFrontendExtensions(); testCandidateSequence(); testReadOnlyRefresh(); testOptionalInputStatistics(); testInputStatisticsUnicodeAndPresentationReuse(); testSyncCompletion(); testPresentationReuse(); testUnicode(); testOptionInvalidation(); testStatusCache(); testCandidateIteratorFailureDoesNotReclosePartialApiState(); testNativeSchemaSwitchSelection(); testFrontendWritesOnlySaveableOptions(); testAsciiToggleSharesSavedOptionRule(); testSessionReuseAndEditorIsolation(); testGeneratedConfigInvalidation(); testUsableDeploymentWarning(); testNativePrewarm(); testRuntimeTypeGroups(); testCurrentSchemaPreference(); std::cout << "engine contract: passed\n";
    }
    catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
