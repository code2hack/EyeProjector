#include <jni.h>
#include <linux/input.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <dirent.h>
#include <fstream>
#include <iomanip>
#include <iterator>
#include <limits.h>
#include <map>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

constexpr int kTap = 65536;
constexpr int kSwipeUp = 65537;
constexpr int kSwipeDown = 65538;
constexpr int kSwipeLeft = 65539;
constexpr int kSwipeRight = 65540;
// Manager contract extension EYEPROJECTOR-V02-ISSUE-79-PIN-HAT-INPUTS-GRANTED:
// HAT0..3 use 65541 + hatIndex*4 + direction (up=0, down=1, left=2, right=3).
// These are learned input tokens, with real axis-edge hold timing; not actions.
constexpr int kHatBase = 65541;
std::atomic<int64_t> nextGeneration{0};
std::atomic<int64_t> nextSource{0};
std::atomic<int64_t> nextPress{0};

int64_t uptime() {
    timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    return static_cast<int64_t>(time.tv_sec) * 1000 + time.tv_nsec / 1000000;
}

std::string quote(const std::string& text) {
    std::ostringstream out;
    out << '"';
    for (unsigned char c : text) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 32 || c >= 127) {
                    // Kernel names are byte strings. Escaping non-ASCII also keeps
                    // JNI's modified-UTF8 constructor away from malformed bytes.
                    out << "\\u00" << std::hex << std::setw(2) << std::setfill('0')
                        << static_cast<unsigned int>(c) << std::dec;
                } else out << c;
        }
    }
    out << '"';
    return out.str();
}

// Stable opaque identifiers only. Raw physical addresses never leave this reader.
std::string identity(const std::string& value) {
    uint64_t a = UINT64_C(14695981039346656037);
    uint64_t b = UINT64_C(7809847782465536322);
    for (unsigned char byte : value) {
        a = (a ^ byte) * UINT64_C(1099511628211);
        b = (b ^ byte) * UINT64_C(14029467366897019727);
    }
    std::ostringstream out;
    out << std::hex << std::setfill('0') << std::setw(16) << a << std::setw(16) << b;
    return out.str();
}

std::string readText(const std::string& path) {
    std::ifstream file(path);
    std::string value;
    std::getline(file, value);
    return value;
}

int readHex(const std::string& path) {
    auto text = readText(path);
    return text.empty() ? 0 : static_cast<int>(strtoul(text.c_str(), nullptr, 16));
}

std::string realPath(const std::string& path) {
    char resolved[PATH_MAX];
    return realpath(path.c_str(), resolved) ? resolved : std::string();
}

template<size_t N> bool bit(const unsigned char (&bits)[N], unsigned int code) {
    return code / 8 < N && (bits[code / 8] & (1U << (code % 8))) != 0;
}

struct Description {
    std::string path, group, collection, name, physical, unique, sysPath;
    input_id id{};
    dev_t device = 0;
    ino_t inode = 0;
    bool hasKeys = false, hasTouch = false;
    bool typeB = false, mt = false, tracking = false, touchButton = false, pressure = false;
    unsigned int hatAxes = 0;
    std::string capabilities;
};

std::string physicalGroup(const Description& description) {
    std::string prefix = std::to_string(description.id.bustype) + ":" +
        std::to_string(description.id.vendor) + ":" + std::to_string(description.id.product) + ":";
    if (!description.unique.empty()) return identity(prefix + "unique:" + description.unique);
    // USB collections can live under different interfaces of one physical USB
    // device. Walk to that USB parent rather than retaining HID/input instance IDs.
    std::string parent = description.sysPath;
    while (!parent.empty() && parent != "/") {
        if (!readText(parent + "/idVendor").empty()) return identity(prefix + "usb:" + parent);
        const auto slash = parent.rfind('/');
        parent = slash == std::string::npos ? std::string() : parent.substr(0, slash);
    }
    std::string physical = description.physical;
    const auto input = physical.rfind("/input");
    if (input != std::string::npos) physical.resize(input);
    if (!physical.empty()) return identity(prefix + "phys:" + physical);
    // Sysfs remains a per-physical fallback, never vendor/product/name alone.
    parent = description.sysPath;
    const auto inputPath = parent.rfind("/input/input");
    if (inputPath != std::string::npos) parent.resize(inputPath);
    // Do not collapse HID instance suffixes: absent a unique address/physical
    // route, that could merge two otherwise identical connected controllers.
    return parent.empty() ? std::string() : identity(prefix + "sys:" + parent);
}

std::vector<Description> discover(std::string& error) {
    std::vector<Description> found;
    DIR* directory = opendir("/dev/input");
    if (!directory) { error = "Input device enumeration is unavailable"; return found; }
    bool unreadable = false;
    while (const dirent* entry = readdir(directory)) {
        const std::string node = entry->d_name;
        if (node.compare(0, 5, "event") != 0 || node.size() == 5 ||
            !std::all_of(node.begin() + 5, node.end(), [](char c) { return c >= '0' && c <= '9'; })) continue;
        Description d;
        d.path = "/dev/input/" + node;
        const std::string sys = "/sys/class/input/" + node + "/device";
        d.sysPath = realPath(sys);
        d.name = readText(sys + "/name");
        d.physical = readText(sys + "/phys");
        d.unique = readText(sys + "/uniq");
        d.id.bustype = static_cast<__u16>(readHex(sys + "/id/bustype"));
        d.id.vendor = static_cast<__u16>(readHex(sys + "/id/vendor"));
        d.id.product = static_cast<__u16>(readHex(sys + "/id/product"));
        d.id.version = static_cast<__u16>(readHex(sys + "/id/version"));
        struct stat st{};
        if (stat(d.path.c_str(), &st) != 0) continue;
        d.device = st.st_rdev;
        d.inode = st.st_ino;
        const int fd = open(d.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd >= 0) {
            char value[4096]{};
            if (ioctl(fd, EVIOCGID, &d.id) < 0) { ::close(fd); continue; }
            if (ioctl(fd, EVIOCGNAME(sizeof(value)), value) >= 0) d.name = value;
            memset(value, 0, sizeof(value));
            if (ioctl(fd, EVIOCGPHYS(sizeof(value)), value) >= 0) d.physical = value;
            memset(value, 0, sizeof(value));
            if (ioctl(fd, EVIOCGUNIQ(sizeof(value)), value) >= 0) d.unique = value;
            unsigned char keys[(KEY_MAX + 8) / 8]{};
            unsigned char axes[(ABS_MAX + 8) / 8]{};
            ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys);
            ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(axes)), axes);
            d.hasKeys = std::any_of(std::begin(keys), std::end(keys), [](unsigned char v) { return v != 0; });
            d.mt = bit(axes, ABS_MT_POSITION_X) && bit(axes, ABS_MT_POSITION_Y);
            d.tracking = bit(axes, ABS_MT_TRACKING_ID);
            d.typeB = d.mt && bit(axes, ABS_MT_SLOT);
            d.touchButton = bit(keys, BTN_TOUCH);
            d.pressure = bit(axes, ABS_PRESSURE);
            for (int axis = ABS_HAT0X; axis <= ABS_HAT3Y; ++axis) {
                if (bit(axes, static_cast<unsigned int>(axis))) d.hatAxes |= 1U << (axis - ABS_HAT0X);
            }
            d.hasKeys |= d.hatAxes != 0;
            d.hasTouch = d.mt || (bit(axes, ABS_X) && bit(axes, ABS_Y) && d.touchButton);
            ::close(fd);
        }
        // Only external USB/Bluetooth HID groups enter inventory. The Phone's
        // internal touchscreen, keys and sensors retain their normal routing.
        if (d.id.bustype != BUS_USB && d.id.bustype != BUS_BLUETOOTH) continue;
        if (fd < 0) unreadable = true;
        d.capabilities = readText(sys + "/capabilities/ev") + ":" +
            readText(sys + "/capabilities/key") + ":" + readText(sys + "/capabilities/abs") +
            ":" + readText(sys + "/capabilities/rel");
        d.group = physicalGroup(d);
        if (d.group.empty()) continue;
        const auto suffix = d.physical.rfind("/input");
        const std::string interface = suffix == std::string::npos ? std::string() : d.physical.substr(suffix);
        d.collection = identity(d.group + ":" + d.name + ":" + interface + ":" + d.capabilities);
        found.push_back(std::move(d));
    }
    closedir(directory);
    if (unreadable) error = "Some input collections could not be read; pinning requires access to every collection in the selected group";
    std::sort(found.begin(), found.end(), [](const Description& a, const Description& b) { return a.path < b.path; });
    return found;
}

std::string inventoryJson(const std::vector<Description>& all, const std::string& error) {
    std::map<std::string, std::vector<const Description*>> groups;
    for (const auto& d : all) groups[d.group].push_back(&d);
    std::ostringstream out;
    out << "{\"devices\":[";
    bool first = true;
    for (const auto& group : groups) {
        const auto& d = *group.second.front();
        bool keys = false, touch = false;
        for (const auto* collection : group.second) { keys |= collection->hasKeys; touch |= collection->hasTouch; }
        if (!first) out << ',';
        first = false;
        out << "{\"key\":" << quote(group.first) << ",\"name\":" << quote(d.name)
            << ",\"vendorId\":" << d.id.vendor << ",\"productId\":" << d.id.product
            << ",\"bus\":" << d.id.bustype << ",\"collections\":" << group.second.size()
            << ",\"hasKeys\":" << (keys ? "true" : "false") << ",\"hasTouch\":" << (touch ? "true" : "false") << '}';
    }
    out << ']';
    if (!error.empty()) out << ",\"error\":" << quote(error);
    out << '}';
    return out.str();
}

struct Press { int64_t id, down; int repeats = 0; };
struct Contact {
    int tracking = -1;
    int x = 0, y = 0;
    bool haveX = false, haveY = false;
    int releasedTracking = -1;
};
struct Node {
    Description description;
    int fd = -1;
    int64_t source = 0;
    bool monotonic = false, dropped = false;
    std::map<int, Press> presses;
    std::set<int> suppressedKeys;
    std::map<int, int> hats;
    std::map<int, Contact> slots;
    int slot = 0;
    Contact single, typeAContact;
    std::vector<Contact> typeAFrame;
    bool touching = false, suppressTouch = false;
    bool gesture = false;
    int primary = -1;
    int primaryTracking = -1;
    double startX = 0, startY = 0, lastX = 0, lastY = 0;
    input_absinfo xAxis{}, yAxis{};
    double slopX = 0, slopY = 0;
};

bool touchMetadata(const Node& node, int code) {
    return node.description.hasTouch &&
        ((code >= BTN_TOOL_PEN && code <= BTN_TOOL_QUINTTAP) || code == BTN_TOUCH ||
         (code >= BTN_TOOL_DOUBLETAP && code <= BTN_TOOL_QUADTAP));
}

std::string label(int code) {
    if (code >= kHatBase && code < kHatBase + 16) {
        static const char* directions[] = {"up", "down", "left", "right"};
        const int token = code - kHatBase;
        return "Hat " + std::to_string(token / 4) + " " + directions[token % 4];
    }
    switch (code) {
        case kTap: return "Touch tap";
        case kSwipeUp: return "Touch swipe up";
        case kSwipeDown: return "Touch swipe down";
        case kSwipeLeft: return "Touch swipe left";
        case kSwipeRight: return "Touch swipe right";
        case KEY_UP: return "Up";
        case KEY_DOWN: return "Down";
        case KEY_LEFT: return "Left";
        case KEY_RIGHT: return "Right";
        case KEY_ENTER: return "Enter";
        case KEY_SPACE: return "Space";
        case KEY_BACK: return "Back";
        case KEY_HOME: return "Home";
        case KEY_VOLUMEUP: return "Volume up";
        case KEY_VOLUMEDOWN: return "Volume down";
        case KEY_PLAYPAUSE: return "Play/pause";
        case BTN_LEFT: return "Left click";
        case BTN_RIGHT: return "Right click";
        case BTN_MIDDLE: return "Middle click";
        case BTN_SOUTH: return "Gamepad south";
        case BTN_EAST: return "Gamepad east";
        case BTN_NORTH: return "Gamepad north";
        case BTN_WEST: return "Gamepad west";
        default: return "Linux key " + std::to_string(code);
    }
}

class Reader {
public:
    Reader(JavaVM* vm, JNIEnv* env, jobject callback, double slopX, double slopY)
        : vm_(vm), touchSlopX_(slopX), touchSlopY_(slopY) {
        callback_ = env->NewGlobalRef(callback);
        const jclass type = env->GetObjectClass(callback);
        onState_ = env->GetMethodID(type, "onNativeState", "(Ljava/lang/String;)V");
        onInput_ = env->GetMethodID(type, "onNativeInput", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(type);
        if (pipe2(wake_, O_NONBLOCK | O_CLOEXEC) != 0) return;
        watch_ = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
        if (watch_ < 0 || inotify_add_watch(watch_, "/dev/input", IN_CREATE | IN_DELETE | IN_MOVED_FROM | IN_MOVED_TO | IN_ATTRIB | IN_DELETE_SELF | IN_MOVE_SELF) < 0) return;
        thread_ = std::thread([this] { run(); });
    }

    ~Reader() {
        request(Command::Stop, "");
        if (thread_.joinable()) thread_.join();
        if (watch_ >= 0) ::close(watch_);
        if (wake_[0] >= 0) ::close(wake_[0]);
        if (wake_[1] >= 0) ::close(wake_[1]);
        JNIEnv* env = nullptr;
        if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) env->DeleteGlobalRef(callback_);
    }

    bool started() const { return thread_.joinable(); }
    enum class Command { Pin, Unpin, Refresh, Stop };

    void request(Command command, std::string key) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            commands_.emplace_back(command, std::move(key));
        }
        const char byte = 1;
        if (wake_[1] >= 0) { const auto ignored = write(wake_[1], &byte, 1); (void) ignored; }
    }

    std::string inventory() {
        std::lock_guard<std::mutex> lock(mutex_);
        return inventory_;
    }

    std::string state() {
        std::lock_guard<std::mutex> lock(mutex_);
        return state_;
    }

private:
    JavaVM* vm_;
    double touchSlopX_, touchSlopY_;
    jobject callback_ = nullptr;
    jmethodID onState_ = nullptr, onInput_ = nullptr;
    JNIEnv* env_ = nullptr;
    int wake_[2]{-1, -1};
    int watch_ = -1;
    std::thread thread_;
    std::mutex mutex_;
    std::deque<std::pair<Command, std::string>> commands_;
    std::string inventory_ = "{\"devices\":[]}";
    std::string state_ = "{\"phase\":\"IDLE\",\"generation\":0,\"intercepted\":false,\"collectionCount\":0}";
    std::vector<Description> available_;
    std::vector<Node> nodes_;
    std::string desired_, deviceName_, discoveryError_;
    int64_t generation_ = 0;

    void callback(jmethodID method, const std::string& json) {
        const jstring value = env_->NewStringUTF(json.c_str());
        if (value) {
            env_->CallVoidMethod(callback_, method, value);
            env_->DeleteLocalRef(value);
        }
        // Kotlin dispatches onto its owner thread. A dead app listener is handled
        // there and requests release; JNI exceptions must not kill this fd owner.
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }

    void publish(const char* phase, bool intercepted, const std::string& error = "") {
        std::ostringstream out;
        out << "{\"phase\":" << quote(phase) << ",\"generation\":" << generation_
            << ",\"intercepted\":" << (intercepted ? "true" : "false")
            << ",\"collectionCount\":" << nodes_.size();
        if (!desired_.empty()) out << ",\"deviceKey\":" << quote(desired_)
            << ",\"deviceName\":" << quote(deviceName_);
        if (!error.empty()) out << ",\"error\":" << quote(error);
        out << '}';
        const std::string json = out.str();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            state_ = json;
        }
        callback(onState_, json);
    }

    void emit(Node& node, int code, const char* phase, const Press& press, int64_t time) {
        const auto& d = node.description;
        std::ostringstream out;
        out << "{\"generation\":" << generation_ << ",\"deviceKey\":" << quote(desired_)
            << ",\"deviceName\":" << quote(deviceName_) << ",\"vendorId\":" << d.id.vendor
            << ",\"productId\":" << d.id.product << ",\"collectionKey\":" << quote(d.collection)
            << ",\"sourceId\":" << node.source << ",\"code\":" << code << ",\"label\":" << quote(label(code))
            << ",\"phase\":" << quote(phase) << ",\"pressId\":" << press.id
            << ",\"downTimeMs\":" << press.down << ",\"eventTimeMs\":" << time
            << ",\"repeatCount\":" << press.repeats << '}';
        callback(onInput_, out.str());
    }

    void cancel(Node& node) {
        const int64_t time = uptime();
        for (const auto& press : node.presses) emit(node, press.first, "CANCEL", press.second, time);
        node.presses.clear();
        node.gesture = false;
        node.primary = -1;
        node.primaryTracking = -1;
    }

    void release() {
        for (auto& node : nodes_) {
            cancel(node);
            ioctl(node.fd, EVIOCGRAB, 0);
            ::close(node.fd);
            node.fd = -1;
        }
        nodes_.clear();
    }

    void baseline(Node& node) {
        node.suppressedKeys.clear();
        unsigned char keys[(KEY_MAX + 8) / 8]{};
        ioctl(node.fd, EVIOCGKEY(sizeof(keys)), keys);
        for (int code = 0; code <= KEY_MAX; ++code) {
            if (bit(keys, static_cast<unsigned int>(code))) node.suppressedKeys.insert(code);
        }
        node.touching = bit(keys, BTN_TOUCH);
        node.single = Contact{};
        node.slots.clear();
        node.typeAFrame.clear();
        node.typeAContact = Contact{};
        node.slot = 0;
        const auto& d = node.description;
        node.hats.clear();
        for (int axis = ABS_HAT0X; axis <= ABS_HAT3Y; ++axis) {
            if ((d.hatAxes & (1U << (axis - ABS_HAT0X))) == 0) continue;
            input_absinfo hat{};
            if (ioctl(node.fd, EVIOCGABS(axis), &hat) == 0) {
                // Initial nonzero positions establish a baseline without DOWN.
                node.hats[axis] = (hat.value > 0) - (hat.value < 0);
            }
        }
        const int xCode = d.mt ? ABS_MT_POSITION_X : ABS_X;
        const int yCode = d.mt ? ABS_MT_POSITION_Y : ABS_Y;
        ioctl(node.fd, EVIOCGABS(xCode), &node.xAxis);
        ioctl(node.fd, EVIOCGABS(yCode), &node.yAxis);
        node.single.x = node.xAxis.value;
        node.single.y = node.yAxis.value;
        node.single.haveX = node.single.haveY = true;
        // Use the Phone's platform touch slop, normalized into each reported
        // axis range; retain any larger noise tolerance reported by the device.
        node.slopX = std::max<double>(node.xAxis.fuzz,
            (static_cast<double>(node.xAxis.maximum) - node.xAxis.minimum) * touchSlopX_);
        node.slopY = std::max<double>(node.yAxis.fuzz,
            (static_cast<double>(node.yAxis.maximum) - node.yAxis.minimum) * touchSlopY_);
        if (d.typeB) {
            input_absinfo slots{};
            if (ioctl(node.fd, EVIOCGABS(ABS_MT_SLOT), &slots) == 0 && slots.maximum >= slots.minimum) {
                node.slot = slots.value;
                const size_t count = static_cast<size_t>(slots.maximum - slots.minimum) + 1;
                std::vector<int32_t> tracking(count + 1), x(count + 1), y(count + 1);
                tracking[0] = ABS_MT_TRACKING_ID; x[0] = ABS_MT_POSITION_X; y[0] = ABS_MT_POSITION_Y;
                if (ioctl(node.fd, EVIOCGMTSLOTS(tracking.size() * sizeof(int32_t)), tracking.data()) >= 0) {
                    ioctl(node.fd, EVIOCGMTSLOTS(x.size() * sizeof(int32_t)), x.data());
                    ioctl(node.fd, EVIOCGMTSLOTS(y.size() * sizeof(int32_t)), y.data());
                    for (size_t i = 0; i < count; ++i) {
                        node.slots[slots.minimum + static_cast<int>(i)] =
                            Contact{tracking[i + 1], x[i + 1], y[i + 1], true, true};
                    }
                }
            }
        } else if (d.pressure && !d.touchButton) {
            input_absinfo pressure{};
            if (ioctl(node.fd, EVIOCGABS(ABS_PRESSURE), &pressure) == 0) node.touching = pressure.value > pressure.minimum;
        }
        const bool toolContact = bit(keys, BTN_TOOL_FINGER) || bit(keys, BTN_TOOL_DOUBLETAP) ||
            bit(keys, BTN_TOOL_TRIPLETAP) || bit(keys, BTN_TOOL_QUADTAP) || bit(keys, BTN_TOOL_QUINTTAP);
        const bool heldSlot = std::any_of(node.slots.begin(), node.slots.end(), [](const auto& slot) {
            return slot.second.tracking >= 0;
        });
        node.suppressTouch = node.touching || toolContact || heldSlot;
        // Type A has no queryable slot snapshot. Require one all-up frame only
        // when the kernel reports an already-held contact through BTN_TOUCH.
        node.gesture = false;
        node.primary = -1;
        node.primaryTracking = -1;
    }

    void refresh() {
        discoveryError_.clear();
        available_ = discover(discoveryError_);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            inventory_ = inventoryJson(available_, discoveryError_);
        }
        if (desired_.empty()) { publish("IDLE", false, discoveryError_); return; }
        std::vector<Description> group;
        for (const auto& d : available_) if (d.group == desired_) group.push_back(d);
        const bool unchanged = !nodes_.empty() && nodes_.size() == group.size() &&
            std::all_of(nodes_.begin(), nodes_.end(), [&group](const Node& node) {
                return std::any_of(group.begin(), group.end(), [&node](const Description& d) {
                    return d.path == node.description.path && d.inode == node.description.inode &&
                        d.device == node.description.device && d.collection == node.description.collection;
                });
            });
        if (unchanged) { publish("ACTIVE", true); return; }
        if (!nodes_.empty()) {
            publish("RELEASING", true);
            release();
        }
        generation_ = ++nextGeneration;
        if (group.empty()) { publish("DISCONNECTED", false, discoveryError_); return; }
        deviceName_ = group.front().name;
        publish("PINNING", false);
        std::vector<Node> acquired;
        std::string failure;
        for (const auto& d : group) {
            Node node;
            node.description = d;
            node.fd = open(d.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
            if (node.fd < 0) { failure = "A selected input collection could not be opened"; break; }
            // Recheck the opened node so replacement of a dynamic event path
            // cannot grab a different physical device after discovery.
            input_id actual{};
            struct stat st{};
            if (fstat(node.fd, &st) != 0 || ioctl(node.fd, EVIOCGID, &actual) < 0 ||
                st.st_rdev != d.device || st.st_ino != d.inode ||
                memcmp(&actual, &d.id, sizeof(actual)) != 0) {
                ::close(node.fd); failure = "The selected device changed during discovery"; break;
            }
            if (ioctl(node.fd, EVIOCGRAB, 1) < 0) {
                ::close(node.fd); failure = "Exclusive interception failed for a selected collection"; break;
            }
            node.source = ++nextSource;
            int clock = CLOCK_MONOTONIC;
            node.monotonic = ioctl(node.fd, EVIOCSCLOCKID, &clock) == 0;
            input_event discarded[64];
            while (read(node.fd, discarded, sizeof(discarded)) > 0) { }
            baseline(node);
            acquired.push_back(std::move(node));
        }
        if (!failure.empty()) {
            for (auto& node : acquired) { ioctl(node.fd, EVIOCGRAB, 0); ::close(node.fd); }
            publish("FAILED", false, failure);
            return;
        }
        nodes_ = std::move(acquired);
        publish("ACTIVE", true);
    }

    void key(Node& node, const input_event& event, int64_t time) {
        const int code = event.code;
        if (touchMetadata(node, code)) {
            if (code == BTN_TOUCH) node.touching = event.value != 0;
            return;
        }
        if (node.suppressedKeys.count(code)) {
            if (event.value == 0) node.suppressedKeys.erase(code);
            return;
        }
        auto found = node.presses.find(code);
        if (event.value == 1) {
            if (found != node.presses.end()) return;
            Press press{++nextPress, time, 0};
            node.presses.emplace(code, press);
            emit(node, code, "DOWN", press, time);
        } else if (event.value == 2 && found != node.presses.end()) {
            ++found->second.repeats;
            emit(node, code, "DOWN", found->second, time);
        } else if (event.value == 0 && found != node.presses.end()) {
            emit(node, code, "UP", found->second, time);
            node.presses.erase(found);
        }
    }

    static int hatToken(int axis, int direction) {
        const int offset = axis - ABS_HAT0X;
        const int heading = offset % 2 == 0 ? (direction < 0 ? 2 : 3) : (direction < 0 ? 0 : 1);
        return kHatBase + (offset / 2) * 4 + heading;
    }

    void hat(Node& node, const input_event& event, int64_t time) {
        const int old = node.hats[event.code];
        const int direction = (event.value > 0) - (event.value < 0);
        if (old == direction) return;
        node.hats[event.code] = direction;
        if (old != 0) {
            const int code = hatToken(event.code, old);
            const auto press = node.presses.find(code);
            if (press != node.presses.end()) {
                emit(node, code, "UP", press->second, time);
                node.presses.erase(press);
            }
        }
        if (direction != 0) {
            const int code = hatToken(event.code, direction);
            const Press press{++nextPress, time, 0};
            node.presses.emplace(code, press);
            emit(node, code, "DOWN", press, time);
        }
    }

    void absolute(Node& node, const input_event& event, int64_t time) {
        if (event.code >= ABS_HAT0X && event.code <= ABS_HAT3Y) {
            hat(node, event, time);
            return;
        }
        if (!node.description.hasTouch) return;
        if (event.code == ABS_MT_SLOT) { node.slot = event.value; return; }
        Contact* contact = node.description.typeB ? &node.slots[node.slot] :
            node.description.mt ? &node.typeAContact : &node.single;
        switch (event.code) {
            case ABS_MT_TRACKING_ID:
                // Keep last coordinates until SYN_REPORT, including a release
                // frame whose final position arrived before TRACKING_ID=-1.
                // Slot coordinates persist across tracking IDs; an unchanged
                // coordinate is legitimately omitted by a type B device.
                contact->releasedTracking = event.value < 0 ? contact->tracking : -1;
                contact->tracking = event.value;
                break;
            case ABS_MT_POSITION_X:
                contact->x = event.value; contact->haveX = true; break;
            case ABS_MT_POSITION_Y:
                contact->y = event.value; contact->haveY = true; break;
            case ABS_X:
                if (!node.description.mt) { contact->x = event.value; contact->haveX = true; }
                break;
            case ABS_Y:
                if (!node.description.mt) { contact->y = event.value; contact->haveY = true; }
                break;
            case ABS_PRESSURE:
                if (!node.description.touchButton) node.touching = event.value > 0;
                break;
            default: break;
        }
    }

    void typeAContact(Node& node) {
        if (node.typeAContact.haveX && node.typeAContact.haveY) {
            node.typeAFrame.push_back(node.typeAContact);
        }
        node.typeAContact = Contact{};
    }

    void frame(Node& node, int64_t time) {
        if (!node.description.hasTouch) return;
        std::vector<std::pair<int, Contact>> active;
        if (node.description.typeB) {
            for (const auto& slot : node.slots) {
                if (slot.second.tracking >= 0) active.emplace_back(slot.first, slot.second);
                if (node.gesture && slot.first == node.primary && slot.second.haveX && slot.second.haveY &&
                    (slot.second.tracking == node.primaryTracking || slot.second.releasedTracking == node.primaryTracking)) {
                    node.lastX = slot.second.x; node.lastY = slot.second.y;
                }
            }
        } else if (node.description.mt) {
            typeAContact(node);
            // Release metadata may precede the last coordinates in a frame.
            // Retain that endpoint, but never treat it as another active contact.
            if (node.gesture && !node.typeAFrame.empty() && !node.description.tracking) {
                const size_t primary = node.primary >= 0 && static_cast<size_t>(node.primary) < node.typeAFrame.size() ?
                    static_cast<size_t>(node.primary) : 0;
                node.lastX = node.typeAFrame[primary].x;
                node.lastY = node.typeAFrame[primary].y;
            }
            if (!node.description.touchButton || node.touching) {
                for (size_t i = 0; i < node.typeAFrame.size(); ++i) {
                    if (!node.description.tracking || node.typeAFrame[i].tracking >= 0) {
                        active.emplace_back(node.description.tracking ? node.typeAFrame[i].tracking : static_cast<int>(i), node.typeAFrame[i]);
                    }
                }
            }
            node.typeAFrame.clear();
        } else {
            if (node.gesture) { node.lastX = node.single.x; node.lastY = node.single.y; }
            if (node.touching) active.emplace_back(0, node.single);
        }
        if (node.suppressTouch) {
            if (active.empty()) node.suppressTouch = false;
            return;
        }
        if (!active.empty()) {
            const auto primary = std::find_if(active.begin(), active.end(), [&node](const auto& c) { return c.first == node.primary; });
            const auto& sample = !node.gesture ? active.front() : primary != active.end() ? *primary : active.front();
            if (!sample.second.haveX || !sample.second.haveY) return;
            if (!node.gesture) {
                node.gesture = true;
                node.primary = sample.first;
                node.primaryTracking = sample.second.tracking;
                node.startX = node.lastX = sample.second.x;
                node.startY = node.lastY = sample.second.y;
            } else if ((!node.description.typeB || sample.second.tracking == node.primaryTracking) && primary != active.end()) {
                node.lastX = sample.second.x; node.lastY = sample.second.y;
            }
            return;
        }
        if (!node.gesture) return;
        node.gesture = false;
        node.primary = -1;
        node.primaryTracking = -1;
        const double dx = node.lastX - node.startX, dy = node.lastY - node.startY;
        int code = kTap;
        if (std::abs(dx) > node.slopX || std::abs(dy) > node.slopY) {
            const double width = std::max(1.0, static_cast<double>(node.xAxis.maximum) - node.xAxis.minimum);
            const double height = std::max(1.0, static_cast<double>(node.yAxis.maximum) - node.yAxis.minimum);
            code = std::abs(dx) / width > std::abs(dy) / height ? (dx < 0 ? kSwipeLeft : kSwipeRight) :
                (dy < 0 ? kSwipeUp : kSwipeDown);
        }
        const Press pulse{++nextPress, time, 0};
        emit(node, code, "DOWN", pulse, time);
        emit(node, code, "UP", pulse, time);
    }

    bool drain(Node& node) {
        input_event events[64];
        for (;;) {
            const ssize_t bytes = read(node.fd, events, sizeof(events));
            if (bytes < 0) {
                if (errno == EINTR) continue;
                return errno == EAGAIN || errno == EWOULDBLOCK;
            }
            if (bytes == 0 || bytes % sizeof(input_event) != 0) return false;
            for (size_t i = 0; i < static_cast<size_t>(bytes) / sizeof(input_event); ++i) {
                const auto& event = events[i];
                const int64_t time = node.monotonic ? static_cast<int64_t>(event.time.tv_sec) * 1000 + event.time.tv_usec / 1000 : uptime();
                if (event.type == EV_SYN && event.code == SYN_DROPPED) {
                    cancel(node);
                    node.dropped = true;
                    continue;
                }
                if (node.dropped) {
                    if (event.type == EV_SYN && event.code == SYN_REPORT) {
                        // The queried baseline describes current kernel state,
                        // so queued pre-snapshot edges (including the remainder
                        // of this batch) must not be replayed as fresh presses.
                        input_event discarded[64];
                        while (read(node.fd, discarded, sizeof(discarded)) > 0) { }
                        baseline(node);
                        node.dropped = false;
                        return true;
                    }
                    continue;
                }
                if (event.type == EV_KEY) key(node, event, time);
                else if (event.type == EV_ABS) absolute(node, event, time);
                else if (event.type == EV_SYN && event.code == SYN_MT_REPORT && node.description.mt && !node.description.typeB) typeAContact(node);
                else if (event.type == EV_SYN && event.code == SYN_REPORT) frame(node, time);
            }
            // Return to poll after each kernel batch so hotplug, Unpin and client
            // death remain responsive even on a continuously reporting sensor.
            return true;
        }
    }

    void run() {
        if (vm_->AttachCurrentThread(&env_, nullptr) != JNI_OK) return;
        generation_ = ++nextGeneration;
        refresh();
        bool running = true;
        while (running) {
            std::vector<pollfd> fds;
            fds.push_back(pollfd{wake_[0], POLLIN, 0});
            fds.push_back(pollfd{watch_, POLLIN, 0});
            for (const auto& node : nodes_) fds.push_back(pollfd{node.fd, POLLIN, 0});
            int result;
            do { result = poll(fds.data(), fds.size(), -1); } while (result < 0 && errno == EINTR);
            if (result < 0) { release(); generation_ = ++nextGeneration; publish("FAILED", false, "The input reader stopped"); break; }
            if (fds[0].revents != 0) {
                char buffer[128];
                while (read(wake_[0], buffer, sizeof(buffer)) > 0) { }
                std::deque<std::pair<Command, std::string>> commands;
                { std::lock_guard<std::mutex> lock(mutex_); commands.swap(commands_); }
                for (auto& command : commands) {
                    if (command.first == Command::Stop) { running = false; break; }
                    if (command.first == Command::Pin) {
                        if (desired_ != command.second || nodes_.empty()) {
                            if (!nodes_.empty()) { publish("RELEASING", true); release(); }
                            desired_ = std::move(command.second);
                            deviceName_.clear();
                        }
                        refresh();
                    } else if (command.first == Command::Unpin) {
                        publish("RELEASING", !nodes_.empty());
                        release();
                        desired_.clear(); deviceName_.clear(); generation_ = ++nextGeneration;
                        publish("IDLE", false);
                    } else refresh();
                }
                // Commands can replace every fd; rebuild the poll mapping.
                continue;
            }
            if (fds[1].revents != 0) {
                alignas(inotify_event) char buffer[4096];
                while (read(watch_, buffer, sizeof(buffer)) > 0) { }
                refresh();
                continue;
            }
            bool lost = false;
            for (size_t i = 0; i < nodes_.size(); ++i) {
                if ((fds[i + 2].revents & (POLLERR | POLLHUP | POLLNVAL)) != 0 ||
                    ((fds[i + 2].revents & POLLIN) != 0 && !drain(nodes_[i]))) { lost = true; break; }
            }
            if (lost) {
                release();
                generation_ = ++nextGeneration;
                publish("DISCONNECTED", false);
                refresh();
            }
        }
        release();
        generation_ = ++nextGeneration;
        publish("IDLE", false);
        vm_->DetachCurrentThread();
        env_ = nullptr;
    }
};

Reader* reader(jlong handle) { return reinterpret_cast<Reader*>(static_cast<intptr_t>(handle)); }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_code2hack_eyeprojector_directhost_hid_NativeHidCapture_create(JNIEnv* env, jobject self, jdouble slopX, jdouble slopY) {
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    auto* value = new Reader(vm, env, self, slopX, slopY);
    if (!value->started()) { delete value; return 0; }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(value));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_code2hack_eyeprojector_directhost_hid_NativeHidCapture_inventory(JNIEnv* env, jobject, jlong handle) {
    auto* value = reader(handle);
    if (!value) return env->NewStringUTF("{\"devices\":[]}");
    return env->NewStringUTF(value->inventory().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_code2hack_eyeprojector_directhost_hid_NativeHidCapture_state(JNIEnv* env, jobject, jlong handle) {
    auto* value = reader(handle);
    return env->NewStringUTF(value ? value->state().c_str() : "{}");
}

extern "C" JNIEXPORT void JNICALL
Java_com_code2hack_eyeprojector_directhost_hid_NativeHidCapture_pin(JNIEnv* env, jobject, jlong handle, jstring key) {
    if (!reader(handle) || !key) return;
    const char* text = env->GetStringUTFChars(key, nullptr);
    if (!text) return;
    reader(handle)->request(Reader::Command::Pin, text);
    env->ReleaseStringUTFChars(key, text);
}

extern "C" JNIEXPORT void JNICALL
Java_com_code2hack_eyeprojector_directhost_hid_NativeHidCapture_unpin(JNIEnv*, jobject, jlong handle) {
    if (reader(handle)) reader(handle)->request(Reader::Command::Unpin, "");
}

extern "C" JNIEXPORT void JNICALL
Java_com_code2hack_eyeprojector_directhost_hid_NativeHidCapture_close(JNIEnv*, jobject, jlong handle) {
    delete reader(handle);
}
