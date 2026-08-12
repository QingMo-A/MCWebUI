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
      << ",\"miscFlags\":" << d3d_misc_flags_ << "}\n}\n";
  return true;
}
