#include "ProofMetrics.h"

#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <system_error>
#include <utility>

ProofMetrics::ProofMetrics(std::string mode, int target_hz,
                           int configured_width, int configured_height,
                           bool accelerated, bool animate,
                           std::string presentation_mode,
                           std::string present_mode)
    : mode_(std::move(mode)), target_hz_(target_hz),
      configured_width_(configured_width), configured_height_(configured_height),
      accelerated_(accelerated), animate_(animate),
      presentation_mode_(std::move(presentation_mode)),
      present_mode_(std::move(present_mode)) {}

void ProofMetrics::RecordFrameRequest() {
  std::lock_guard<std::mutex> lock(mutex_);
  requests_.push_back(Clock::now());
}

void ProofMetrics::RecordWindowlessFrameRate(int configured_rate_hz) {
  std::lock_guard<std::mutex> lock(mutex_);
  configured_windowless_frame_rate_ = configured_rate_hz;
}

void ProofMetrics::RecordPaint(int width, int height, std::size_t dirty_rect_count) {
  std::lock_guard<std::mutex> lock(mutex_);
  paints_.push_back(Clock::now());
  dirty_rects_ += static_cast<std::uint64_t>(dirty_rect_count);
  estimated_bytes_ += static_cast<std::uint64_t>(width) * height * 4u;
  actual_width_ = width;
  actual_height_ = height;
}

void ProofMetrics::RecordAcceleratedPaint(std::size_t dirty_rect_count, void* handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  accelerated_paints_.push_back(Clock::now());
  accelerated_dirty_rects_ += static_cast<std::uint64_t>(dirty_rect_count);
  if (handle != last_handle_) {
    ++handle_changes_;
    last_handle_ = handle;
  }
}

void ProofMetrics::RecordD3D(bool opened, unsigned width, unsigned height,
                             unsigned cef_color_type, unsigned dxgi_format,
                             unsigned mip_levels, unsigned array_size,
                             unsigned sample_count, unsigned usage,
                             unsigned bind_flags, unsigned cpu_access_flags,
                             unsigned misc_flags, long result) {
  std::lock_guard<std::mutex> lock(mutex_);
  d3d_attempted_ = true;
  d3d_result_ = result;
  if (opened) {
    d3d_opened_ = true;
    d3d_width_ = width;
    d3d_height_ = height;
    d3d_color_type_ = cef_color_type;
    d3d_dxgi_format_ = dxgi_format;
    d3d_mip_levels_ = mip_levels;
    d3d_array_size_ = array_size;
    d3d_sample_count_ = sample_count;
    d3d_usage_ = usage;
    d3d_bind_flags_ = bind_flags;
    d3d_cpu_access_flags_ = cpu_access_flags;
    d3d_misc_flags_ = misc_flags;
  }
}

void ProofMetrics::RecordGpuCopy() {
  std::lock_guard<std::mutex> lock(mutex_);
  gpu_copies_.push_back(Clock::now());
}

void ProofMetrics::RecordGpuCopyCompleted() {
  std::lock_guard<std::mutex> lock(mutex_);
  gpu_copies_completed_.push_back(Clock::now());
}

void ProofMetrics::RecordPublished() {
  std::lock_guard<std::mutex> lock(mutex_);
  published_generations_.push_back(Clock::now());
}

void ProofMetrics::RecordConsumerFrame() {
  std::lock_guard<std::mutex> lock(mutex_);
  consumer_frames_.push_back(Clock::now());
}

void ProofMetrics::RecordPresent(bool has_generation, bool new_generation) {
  std::lock_guard<std::mutex> lock(mutex_);
  const auto now = Clock::now();
  presents_.push_back(now);
  ++presented_frames_;
  if (!has_generation) {
    ++no_generation_presented_frames_;
    return;
  }
  if (new_generation) {
    new_generation_presents_.push_back(now);
    ++new_generation_presented_frames_;
  } else {
    repeated_generation_presents_.push_back(now);
    ++repeated_generation_presented_frames_;
  }
}

void ProofMetrics::RecordPresented() {
  RecordPresent(true, true);
}

void ProofMetrics::RecordRaf(std::uint64_t callbacks, double rate_hz,
                             double median_ms, double p95_ms, double max_ms) {
  std::lock_guard<std::mutex> lock(mutex_);
  raf_ = {callbacks, rate_hz, median_ms, p95_ms, max_ms};
}

void ProofMetrics::RecordLoad(bool success) {
  std::lock_guard<std::mutex> lock(mutex_);
  load_finished_ = true;
  load_success_ = success;
}

void ProofMetrics::RecordLabInput(const std::string& kind) {
  std::lock_guard<std::mutex> lock(mutex_);
  ++lab_input_events_;
  if (std::find(lab_input_kinds_.begin(), lab_input_kinds_.end(), kind) == lab_input_kinds_.end())
    lab_input_kinds_.push_back(kind);
  lab_input_observations_.push_back(kind);
}
void ProofMetrics::RecordNativeWindowMessage(const std::string&) {
  std::lock_guard<std::mutex> lock(mutex_);
  ++native_window_messages_;
}
void ProofMetrics::RecordInputDispatch(const std::string&) {
  std::lock_guard<std::mutex> lock(mutex_);
  ++cef_input_dispatches_;
}

void ProofMetrics::RecordAlphaAcceptance(bool passed, const std::string& color_space,
                                         unsigned tolerance, unsigned x, unsigned y,
                                         unsigned actual_b, unsigned actual_g,
                                         unsigned actual_r, unsigned actual_a,
                                         unsigned expected_b, unsigned expected_g,
                                         unsigned expected_r, unsigned expected_a) {
  std::lock_guard<std::mutex> lock(mutex_);
  alpha_attempted_ = true;
  alpha_passed_ = alpha_passed_ && passed;
  if (alpha_samples_.empty()) alpha_passed_ = passed;
  alpha_color_space_ = color_space;
  alpha_tolerance_ = tolerance;
  alpha_samples_.push_back({passed, x, y, actual_b, actual_g, actual_r, actual_a,
                            expected_b, expected_g, expected_r, expected_a});
}

void ProofMetrics::BeginRealCefAlphaAcceptance(const std::string& model) {
  std::lock_guard<std::mutex> lock(mutex_);
  alpha_model_ = model;
  real_cef_alpha_performed_ = true;
  real_cef_alpha_passed_ = true;
}

void ProofMetrics::RecordRealCefRawSample(const std::string& name, int x, int y,
                                          unsigned actual_b, unsigned actual_g,
                                          unsigned actual_r, unsigned actual_a,
                                          unsigned expected_b, unsigned expected_g,
                                          unsigned expected_r, unsigned expected_a,
                                          bool passed) {
  std::lock_guard<std::mutex> lock(mutex_);
  real_cef_raw_samples_[name] = {passed, x, y, actual_b, actual_g, actual_r, actual_a,
                                 expected_b, expected_g, expected_r, expected_a};
  real_cef_alpha_passed_ = real_cef_alpha_passed_ && passed;
}

void ProofMetrics::RecordRealCefCompositionSample(const std::string& name, int x, int y,
                                                  unsigned actual_b, unsigned actual_g,
                                                  unsigned actual_r, unsigned actual_a,
                                                  unsigned expected_b, unsigned expected_g,
                                                  unsigned expected_r, unsigned expected_a,
                                                  bool passed) {
  std::lock_guard<std::mutex> lock(mutex_);
  real_cef_composition_samples_[name] = {passed, x, y, actual_b, actual_g, actual_r, actual_a,
                                         expected_b, expected_g, expected_r, expected_a};
  real_cef_alpha_passed_ = real_cef_alpha_passed_ && passed;
}

void ProofMetrics::FinishRealCefAlphaAcceptance(bool passed) {
  std::lock_guard<std::mutex> lock(mutex_);
  real_cef_alpha_performed_ = true;
  real_cef_alpha_passed_ = real_cef_alpha_passed_ && passed;
}

void ProofMetrics::BeginOpenGLInterop(bool extension_nv, bool extension_nv2,
                                      bool entry_points_complete,
                                      const std::string& vendor,
                                      const std::string& renderer,
                                      const std::string& version,
                                      const std::string& extensions,
                                      const std::string& adapter,
                                      std::uint32_t luid_high,
                                      std::uint32_t luid_low) {
  std::lock_guard<std::mutex> lock(mutex_);
  gl_attempted_ = true;
  gl_extension_nv_ = extension_nv;
  gl_extension_nv2_ = extension_nv2;
  gl_entry_points_complete_ = entry_points_complete;
  gl_supported_ = extension_nv2 && entry_points_complete;
  gl_status_ = gl_supported_ ? "READY" : "UNSUPPORTED";
  gl_vendor_ = vendor;
  gl_renderer_ = renderer;
  gl_version_ = version;
  gl_extensions_ = extensions;
  gl_dxgi_adapter_ = adapter;
  gl_dxgi_luid_high_ = luid_high;
  gl_dxgi_luid_low_ = luid_low;
}

void ProofMetrics::RecordOpenGLDevice(bool opened, unsigned last_error) {
  std::lock_guard<std::mutex> lock(mutex_);
  gl_device_opened_ = opened;
  gl_open_last_error_ = last_error;
  if (!opened && gl_supported_) gl_status_ = "FAILED";
}

void ProofMetrics::RecordOpenGLRegistration(bool registered, unsigned, unsigned last_error) {
  std::lock_guard<std::mutex> lock(mutex_);
  ++gl_register_attempts_;
  if (registered) ++gl_register_successes_;
  else {
    ++gl_register_failures_;
    gl_open_last_error_ = last_error;
    if (gl_supported_) gl_status_ = "FAILED";
  }
}

void ProofMetrics::RecordOpenGLLock(bool locked, unsigned last_error) {
  std::lock_guard<std::mutex> lock(mutex_);
  ++gl_lock_attempts_;
  if (locked) ++gl_lock_successes_;
  else {
    ++gl_lock_failures_;
    gl_open_last_error_ = last_error;
  }
}

void ProofMetrics::RecordOpenGLUnlock(bool unlocked, unsigned last_error) {
  std::lock_guard<std::mutex> lock(mutex_);
  ++gl_unlock_attempts_;
  if (unlocked) ++gl_unlock_successes_;
  else {
    ++gl_unlock_failures_;
    gl_open_last_error_ = last_error;
  }
}

void ProofMetrics::RecordOpenGLFrame(bool new_generation) {
  std::lock_guard<std::mutex> lock(mutex_);
  ++gl_frames_;
  if (new_generation) ++gl_new_frames_;
  else ++gl_repeat_frames_;
}

void ProofMetrics::RecordOpenGLDrop() {
  std::lock_guard<std::mutex> lock(mutex_);
  ++gl_drops_;
}

void ProofMetrics::RecordOpenGLSample(const std::string& name, bool composition,
                                      unsigned actual_r, unsigned actual_g,
                                      unsigned actual_b, unsigned actual_a,
                                      unsigned expected_r, unsigned expected_g,
                                      unsigned expected_b, unsigned expected_a,
                                      bool passed) {
  std::lock_guard<std::mutex> lock(mutex_);
  gl_samples_[name] = {composition, passed, actual_r, actual_g, actual_b, actual_a,
                       expected_r, expected_g, expected_b, expected_a};
}

void ProofMetrics::FinishOpenGLProof(bool performed, bool passed, bool flipped_y) {
  std::lock_guard<std::mutex> lock(mutex_);
  gl_proof_performed_ = performed;
  gl_proof_passed_ = passed;
  gl_texture_y_flipped_ = flipped_y;
  if (performed && gl_supported_) gl_status_ = passed ? "PASS" : "FAILED";
}

TimingSummary ProofMetrics::Summarize(
    const std::vector<Clock::time_point>& samples) {
  TimingSummary result;
  result.callbacks = samples.size();
  if (samples.size() < 2) return result;
  std::vector<double> intervals;
  intervals.reserve(samples.size() - 1);
  for (std::size_t i = 1; i < samples.size(); ++i) {
    intervals.push_back(std::chrono::duration<double, std::milli>(
        samples[i] - samples[i - 1]).count());
  }
  std::sort(intervals.begin(), intervals.end());
  const double elapsed = std::chrono::duration<double>(
      samples.back() - samples.front()).count();
  result.rate_hz = elapsed > 0.0 ? (samples.size() - 1) / elapsed : 0.0;
  const auto percentile = [&intervals](double value) {
    const std::size_t index = static_cast<std::size_t>(
        std::ceil(value * intervals.size()) - 1.0);
    return intervals[std::min(index, intervals.size() - 1)];
  };
  result.median_ms = percentile(0.50);
  result.p95_ms = percentile(0.95);
  result.max_ms = intervals.back();
  return result;
}

std::string ProofMetrics::JsonTiming(const TimingSummary& value) {
  std::ostringstream out;
  out << std::fixed << std::setprecision(3)
      << "{\"callbacks\":" << value.callbacks
      << ",\"rateHz\":" << value.rate_hz
      << ",\"medianMs\":" << value.median_ms
      << ",\"p95Ms\":" << value.p95_ms
      << ",\"maxMs\":" << value.max_ms << "}";
  return out.str();
}

namespace {
std::string JsonString(const std::string& value) {
  std::ostringstream escaped;
  for (const unsigned char character : value) {
    switch (character) {
      case '\\': escaped << "\\\\"; break;
      case '"': escaped << "\\\""; break;
      case '\n': escaped << "\\n"; break;
      case '\r': escaped << "\\r"; break;
      case '\t': escaped << "\\t"; break;
      default:
        if (character < 0x20) {
          escaped << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                  << static_cast<unsigned>(character) << std::dec << std::setfill(' ');
        } else {
          escaped << character;
        }
    }
  }
  return escaped.str();
}
}  // namespace

void ProofMetrics::RecordChildProcess(const std::string& process_type,
                                      const std::string& command_line) {
  std::lock_guard<std::mutex> lock(mutex_);
  ++child_process_launches_;
  if (process_type == "gpu-process") {
    ++gpu_process_launches_;
    last_gpu_command_line_ = command_line;
  } else if (process_type == "renderer") {
    ++render_process_launches_;
  }
}

std::string ProofMetrics::FormatLine() const {
  std::lock_guard<std::mutex> lock(mutex_);
  const auto requests = Summarize(requests_);
  const auto paints = Summarize(paints_);
  const auto accelerated = Summarize(accelerated_paints_);
  const auto copies = Summarize(gpu_copies_);
  const auto completed_copies = Summarize(gpu_copies_completed_);
  const auto published = Summarize(published_generations_);
  const auto consumers = Summarize(consumer_frames_);
  const auto presents = Summarize(presents_);
  std::ostringstream out;
  out << std::fixed << std::setprecision(2)
      << "[direct-cef-proof] mode=" << mode_
      << " target_hz=" << target_hz_
      << " configured_windowless_frame_rate=" << configured_windowless_frame_rate_
      << " requests_hz=" << requests.rate_hz
      << " raf_hz=" << raf_.rate_hz
      << " paint_hz=" << paints.rate_hz
      << " accelerated_hz=" << accelerated.rate_hz
      << " copy_hz=" << copies.rate_hz
      << " copy_complete_hz=" << completed_copies.rate_hz
      << " publish_hz=" << published.rate_hz
      << " consumer_hz=" << consumers.rate_hz
      << " present_hz=" << presents.rate_hz
      << " gpu_launches=" << gpu_process_launches_
      << " d3d_opened=" << (d3d_opened_ ? "true" : "false")
      << " size=" << actual_width_ << "x" << actual_height_;
  return out.str();
}

bool ProofMetrics::WriteJson(const std::string& path) const {
  std::lock_guard<std::mutex> lock(mutex_);
  const auto requests = Summarize(requests_);
  const auto paints = Summarize(paints_);
  const auto accelerated = Summarize(accelerated_paints_);
  const auto copies = Summarize(gpu_copies_);
  const auto completed_copies = Summarize(gpu_copies_completed_);
  const auto published = Summarize(published_generations_);
  const auto consumers = Summarize(consumer_frames_);
  const auto presents = Summarize(presents_);
  const auto new_presents = Summarize(new_generation_presents_);
  const auto repeated_presents = Summarize(repeated_generation_presents_);
  const std::uint64_t classified_presents =
      new_generation_presented_frames_ + repeated_generation_presented_frames_ +
      no_generation_presented_frames_;
  const double duration = std::chrono::duration<double>(Clock::now() - started_).count();
  const std::filesystem::path output(path);
  std::error_code directory_error;
  if (output.has_parent_path())
    std::filesystem::create_directories(output.parent_path(), directory_error);
  if (directory_error) return false;
  std::ofstream out(output, std::ios::binary | std::ios::trunc);
  if (!out) return false;
  const auto json_real_samples = [](const std::map<std::string, RealAlphaSample>& samples) {
    std::ostringstream value;
    value << "{";
    bool first = true;
    for (const auto& entry : samples) {
      if (!first) value << ',';
      first = false;
      const auto& sample = entry.second;
      value << "\"" << JsonString(entry.first) << "\":{";
      value << "\"passed\":" << (sample.passed ? "true" : "false")
            << ",\"x\":" << sample.x << ",\"y\":" << sample.y
            << ",\"actual\":{\"b\":" << sample.actual_b << ",\"g\":"
            << sample.actual_g << ",\"r\":" << sample.actual_r << ",\"a\":"
            << sample.actual_a << "},\"expected\":{\"b\":" << sample.expected_b
            << ",\"g\":" << sample.expected_g << ",\"r\":" << sample.expected_r
            << ",\"a\":" << sample.expected_a << "}}";
    }
    value << "}";
    return value.str();
  };
  // Derive the aggregate from the samples that will be serialized. This keeps
  // the verdict honest if a callback exits early after recording a sample (and
  // prevents a stale intermediate flag from disagreeing with the JSON rows).
  const auto all_real_samples_passed = [](const std::map<std::string, RealAlphaSample>& samples) {
    if (samples.size() != 5) return false;
    for (const auto& entry : samples) {
      if (!entry.second.passed) return false;
    }
    return true;
  };
  const bool real_cef_alpha_verdict = real_cef_alpha_performed_ &&
      all_real_samples_passed(real_cef_raw_samples_) &&
      all_real_samples_passed(real_cef_composition_samples_);
  out << std::fixed << std::setprecision(3)
      << "{\n  \"schemaVersion\":1,\n  \"mode\":\"" << mode_ << "\",\n"
      << "  \"targetHz\":" << target_hz_ << ",\n"
      << "  \"requestedTargetHz\":" << target_hz_ << ",\n"
      << "  \"configuredWindowlessFrameRate\":"
      << configured_windowless_frame_rate_ << ",\n"
      << "  \"acceleratedRequested\":" << (accelerated_ ? "true" : "false") << ",\n"
      << "  \"animationEnabled\":" << (animate_ ? "true" : "false") << ",\n"
      << "  \"presentationMode\":\"" << presentation_mode_
      << "\",\"presentMode\":\"" << present_mode_ << "\",\n"
      << "  \"configuredSize\":{\"width\":" << configured_width_
      << ",\"height\":" << configured_height_ << "},\n"
      << "  \"actualSize\":{\"width\":" << actual_width_
      << ",\"height\":" << actual_height_ << "},\n"
      << "  \"durationSeconds\":" << duration << ",\n"
      << "  \"load\":{\"finished\":" << (load_finished_ ? "true" : "false")
      << ",\"success\":" << (load_success_ ? "true" : "false") << "},\n"
      << "  \"frameRequests\":" << JsonTiming(requests) << ",\n"
      << "  \"browserRaf\":" << JsonTiming(raf_) << ",\n"
      << "  \"cpuPaint\":" << JsonTiming(paints).substr(0, JsonTiming(paints).size() - 1)
      << ",\"dirtyRects\":" << dirty_rects_
      << ",\"estimatedBytes\":" << estimated_bytes_ << "},\n"
      << "  \"acceleratedPaint\":" << JsonTiming(accelerated).substr(0, JsonTiming(accelerated).size() - 1)
      << ",\"dirtyRects\":" << accelerated_dirty_rects_
      << ",\"handleChanges\":" << handle_changes_ << "},\n"
      << "  \"gpuCopies\":" << JsonTiming(copies) << ",\n"
      << "  \"gpuCopiesCompleted\":" << JsonTiming(completed_copies) << ",\n"
      << "  \"publishedGenerations\":" << JsonTiming(published) << ",\n"
      << "  \"consumerFrames\":" << JsonTiming(consumers) << ",\n"
      << "  \"consumerIterations\":" << JsonTiming(consumers) << ",\n"
      << "  \"presents\":" << JsonTiming(presents) << ",\n"
      << "  \"newGenerationPresents\":" << JsonTiming(new_presents) << ",\n"
      << "  \"repeatedGenerationPresents\":" << JsonTiming(repeated_presents) << ",\n"
      << "  \"presentedFrames\":" << presented_frames_
      << ",\"newGenerationPresentedFrames\":" << new_generation_presented_frames_
      << ",\"repeatedGenerationPresentedFrames\":" << repeated_generation_presented_frames_
      << ",\"noGenerationPresentedFrames\":" << no_generation_presented_frames_
      << ",\"presentationAccounting\":{\"classifiedFrames\":"
      << classified_presents << ",\"matchesPresentedFrames\":"
      << (classified_presents == presented_frames_ ? "true" : "false") << "},\n"
      << "  \"gpuDiagnostics\":{\"childProcessLaunches\":"
      << child_process_launches_ << ",\"gpuProcessLaunches\":"
      << gpu_process_launches_ << ",\"renderProcessLaunches\":"
      << render_process_launches_ << ",\"gpuProcessObserved\":"
      << (gpu_process_launches_ > 0 ? "true" : "false")
      << ",\"acceleratedCallbackObserved\":"
      << (accelerated.callbacks > 0 ? "true" : "false")
      << ",\"d3dOpenSharedResourceSucceeded\":"
      << (d3d_opened_ ? "true" : "false")
      << ",\"lastGpuCommandLine\":\"" << JsonString(last_gpu_command_line_)
      << "\"},\n"
      << "  \"d3d11\":{\"attempted\":" << (d3d_attempted_ ? "true" : "false")
      << ",\"opened\":" << (d3d_opened_ ? "true" : "false")
      << ",\"hresult\":" << d3d_result_
      << ",\"width\":" << d3d_width_ << ",\"height\":" << d3d_height_
      << ",\"cefColorType\":" << d3d_color_type_
      << ",\"dxgiFormat\":" << d3d_dxgi_format_
      << ",\"mipLevels\":" << d3d_mip_levels_
      << ",\"arraySize\":" << d3d_array_size_
      << ",\"sampleCount\":" << d3d_sample_count_
      << ",\"usage\":" << d3d_usage_
      << ",\"bindFlags\":" << d3d_bind_flags_
      << ",\"cpuAccessFlags\":" << d3d_cpu_access_flags_
      << ",\"miscFlags\":" << d3d_misc_flags_ << "},\n"
      << "  \"alphaAcceptance\":{\"attempted\":"
      << (alpha_attempted_ ? "true" : "false") << ",\"passed\":"
      << (alpha_attempted_ && alpha_passed_ ? "true" : "false")
      << ",\"colorSpace\":\"" << JsonString(alpha_color_space_)
      << "\",\"tolerance\":" << alpha_tolerance_ << ",\"samples\":[";
  for (std::size_t index = 0; index < alpha_samples_.size(); ++index) {
    const auto& sample = alpha_samples_[index];
    if (index) out << ',';
    out << "{\"passed\":" << (sample.passed ? "true" : "false")
        << ",\"x\":" << sample.x << ",\"y\":" << sample.y
        << ",\"actual\":{\"b\":" << sample.actual_b << ",\"g\":"
        << sample.actual_g << ",\"r\":" << sample.actual_r << ",\"a\":"
        << sample.actual_a << "},\"expected\":{\"b\":" << sample.expected_b
        << ",\"g\":" << sample.expected_g << ",\"r\":" << sample.expected_r
        << ",\"a\":" << sample.expected_a << "}}";
  }
  out << "]},\n  \"interactiveInput\":{\"events\":" << lab_input_events_ << ",\"nativeWindowMessages\":" << native_window_messages_ << ",\"cefInputDispatches\":" << cef_input_dispatches_ << ",\"kinds\":[";
  for (std::size_t index = 0; index < lab_input_kinds_.size(); ++index) {
    if (index) out << ',';
    out << "\"" << JsonString(lab_input_kinds_[index]) << "\"";
  }
  out << "],\"observations\":[";
  for (std::size_t index = 0; index < lab_input_observations_.size(); ++index) {
    if (index) out << ',';
    out << "\"" << JsonString(lab_input_observations_[index]) << "\"";
  }
  out << "]},\n  \"realCefAlphaAcceptance\":{\"performed\":"
      << (real_cef_alpha_performed_ ? "true" : "false") << ",\"passed\":"
      << (real_cef_alpha_verdict ? "true" : "false")
      << ",\"rawTexture\":" << json_real_samples(real_cef_raw_samples_)
      << ",\"composition\":" << json_real_samples(real_cef_composition_samples_)
      << "},\n  \"alphaModel\":\"" << JsonString(alpha_model_) << "\",\n"
      << "  \"openGlInterop\":{\"attempted\":" << (gl_attempted_ ? "true" : "false")
      << ",\"status\":\"" << JsonString(gl_status_) << "\""
      << ",\"supported\":" << (gl_supported_ ? "true" : "false")
      << ",\"extensions\":{\"nv\":" << (gl_extension_nv_ ? "true" : "false")
      << ",\"nvInterop2\":" << (gl_extension_nv2_ ? "true" : "false")
      << ",\"entryPointsComplete\":" << (gl_entry_points_complete_ ? "true" : "false") << "}"
      << ",\"identity\":{\"vendor\":\"" << JsonString(gl_vendor_)
      << "\",\"renderer\":\"" << JsonString(gl_renderer_)
      << "\",\"version\":\"" << JsonString(gl_version_)
      << "\",\"extensions\":\"" << JsonString(gl_extensions_)
      << "\",\"dxgiAdapter\":\"" << JsonString(gl_dxgi_adapter_)
      << "\",\"luidHigh\":" << gl_dxgi_luid_high_ << ",\"luidLow\":" << gl_dxgi_luid_low_ << "}"
      << ",\"device\":{\"opened\":" << (gl_device_opened_ ? "true" : "false")
      << ",\"lastError\":" << gl_open_last_error_ << "}"
      << ",\"registration\":{\"attempts\":" << gl_register_attempts_
      << ",\"successes\":" << gl_register_successes_
      << ",\"failures\":" << gl_register_failures_
      << ",\"registeredSlotCount\":" << gl_register_successes_
      << ",\"expectedSlotCount\":3}"
      << ",\"lock\":{\"attempts\":" << gl_lock_attempts_ << ",\"successes\":" << gl_lock_successes_
      << ",\"failures\":" << gl_lock_failures_ << "}"
      << ",\"unlock\":{\"attempts\":" << gl_unlock_attempts_ << ",\"successes\":" << gl_unlock_successes_
      << ",\"failures\":" << gl_unlock_failures_ << "}"
      << ",\"frames\":{\"total\":" << gl_frames_ << ",\"new\":" << gl_new_frames_
      << ",\"repeat\":" << gl_repeat_frames_ << ",\"drops\":" << gl_drops_ << "}"
      << ",\"proof\":{\"performed\":" << (gl_proof_performed_ ? "true" : "false")
      << ",\"passed\":" << (gl_proof_passed_ ? "true" : "false")
      << ",\"textureYFlipped\":" << (gl_texture_y_flipped_ ? "true" : "false")
      << ",\"samples\":[";
  bool first_gl_sample = true;
  for (const auto& entry : gl_samples_) {
    if (!first_gl_sample) out << ',';
    first_gl_sample = false;
    const auto& sample = entry.second;
    out << "{\"name\":\"" << JsonString(entry.first) << "\",\"composition\":"
        << (sample.composition ? "true" : "false") << ",\"passed\":"
        << (sample.passed ? "true" : "false") << ",\"actual\":{\"r\":"
        << sample.actual_r << ",\"g\":" << sample.actual_g << ",\"b\":" << sample.actual_b
        << ",\"a\":" << sample.actual_a << "},\"expected\":{\"r\":" << sample.expected_r
        << ",\"g\":" << sample.expected_g << ",\"b\":" << sample.expected_b
        << ",\"a\":" << sample.expected_a << "}}";
  }
  out << "]}\n  }\n}\n";
  return true;
}
