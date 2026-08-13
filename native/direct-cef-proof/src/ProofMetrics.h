#pragma once

#include <chrono>
#include <cstdint>
#include <mutex>
#include <map>
#include <string>
#include <vector>

struct TimingSummary {
  std::uint64_t callbacks = 0;
  double rate_hz = 0.0;
  double median_ms = 0.0;
  double p95_ms = 0.0;
  double max_ms = 0.0;
};

class ProofMetrics final {
 public:
  using Clock = std::chrono::steady_clock;

  ProofMetrics(std::string mode, int target_hz, int configured_width,
               int configured_height, bool accelerated, bool animate,
               std::string presentation_mode, std::string present_mode);
  void RecordFrameRequest();
  void RecordWindowlessFrameRate(int configured_rate_hz);
  void RecordPaint(int width, int height, std::size_t dirty_rect_count);
  void RecordAcceleratedPaint(std::size_t dirty_rect_count, void* handle);
  void RecordD3D(bool opened, unsigned width, unsigned height,
                 unsigned cef_color_type, unsigned dxgi_format,
                 unsigned mip_levels, unsigned array_size,
                 unsigned sample_count, unsigned usage, unsigned bind_flags,
                 unsigned cpu_access_flags, unsigned misc_flags, long result);
  void RecordGpuCopy();
  void RecordGpuCopyCompleted();
  void RecordPublished();
  void RecordConsumerFrame();
  void RecordPresent(bool has_generation, bool new_generation);
  void RecordPresented();
  void RecordChildProcess(const std::string& process_type,
                          const std::string& command_line);
  void RecordRaf(std::uint64_t callbacks, double rate_hz, double median_ms,
                 double p95_ms, double max_ms);
  void RecordLoad(bool success);
  void RecordLabInput(const std::string& kind);
  void RecordNativeWindowMessage(const std::string& kind);
  void RecordInputDispatch(const std::string& kind);
  void RecordAlphaAcceptance(bool passed, const std::string& color_space,
                             unsigned tolerance, unsigned x, unsigned y,
                             unsigned actual_b, unsigned actual_g,
                             unsigned actual_r, unsigned actual_a,
                             unsigned expected_b, unsigned expected_g,
                             unsigned expected_r, unsigned expected_a);
  void BeginRealCefAlphaAcceptance(const std::string& model);
  void RecordRealCefRawSample(const std::string& name, int x, int y,
                              unsigned actual_b, unsigned actual_g,
                              unsigned actual_r, unsigned actual_a,
                              unsigned expected_b, unsigned expected_g,
                              unsigned expected_r, unsigned expected_a,
                              bool passed);
  void RecordRealCefCompositionSample(const std::string& name, int x, int y,
                                      unsigned actual_b, unsigned actual_g,
                                      unsigned actual_r, unsigned actual_a,
                                      unsigned expected_b, unsigned expected_g,
                                      unsigned expected_r, unsigned expected_a,
                                      bool passed);
  void FinishRealCefAlphaAcceptance(bool passed);
  std::string FormatLine() const;
  bool WriteJson(const std::string& path) const;

 private:
  static TimingSummary Summarize(const std::vector<Clock::time_point>& samples);
  static std::string JsonTiming(const TimingSummary& value);

  const std::string mode_;
  const int target_hz_;
  const int configured_width_;
  const int configured_height_;
  const bool accelerated_;
  const bool animate_;
  const std::string presentation_mode_;
  const std::string present_mode_;
  int configured_windowless_frame_rate_ = 0;
  const Clock::time_point started_ = Clock::now();
  mutable std::mutex mutex_;
  std::vector<Clock::time_point> requests_;
  std::vector<Clock::time_point> paints_;
  std::vector<Clock::time_point> accelerated_paints_;
  std::vector<Clock::time_point> gpu_copies_;
  std::vector<Clock::time_point> gpu_copies_completed_;
  std::vector<Clock::time_point> published_generations_;
  std::vector<Clock::time_point> consumer_frames_;
  std::vector<Clock::time_point> presents_;
  std::vector<Clock::time_point> new_generation_presents_;
  std::vector<Clock::time_point> repeated_generation_presents_;
  std::uint64_t dirty_rects_ = 0;
  std::uint64_t estimated_bytes_ = 0;
  std::uint64_t accelerated_dirty_rects_ = 0;
  std::uint64_t handle_changes_ = 0;
  std::uint64_t presented_frames_ = 0;
  std::uint64_t new_generation_presented_frames_ = 0;
  std::uint64_t repeated_generation_presented_frames_ = 0;
  std::uint64_t no_generation_presented_frames_ = 0;
  std::uint64_t lab_input_events_ = 0;
  std::uint64_t native_window_messages_ = 0;
  std::uint64_t cef_input_dispatches_ = 0;
  std::vector<std::string> lab_input_kinds_;
  std::vector<std::string> lab_input_observations_;
  struct AlphaSample {
    bool passed = false;
    unsigned x = 0;
    unsigned y = 0;
    unsigned actual_b = 0;
    unsigned actual_g = 0;
    unsigned actual_r = 0;
    unsigned actual_a = 0;
    unsigned expected_b = 0;
    unsigned expected_g = 0;
    unsigned expected_r = 0;
    unsigned expected_a = 0;
  };
  bool alpha_attempted_ = false;
  bool alpha_passed_ = false;
  std::string alpha_color_space_;
  unsigned alpha_tolerance_ = 0;
  std::vector<AlphaSample> alpha_samples_;
  std::string alpha_model_;
  bool real_cef_alpha_performed_ = false;
  bool real_cef_alpha_passed_ = false;
  struct RealAlphaSample {
    bool passed = false;
    int x = 0;
    int y = 0;
    unsigned actual_b = 0;
    unsigned actual_g = 0;
    unsigned actual_r = 0;
    unsigned actual_a = 0;
    unsigned expected_b = 0;
    unsigned expected_g = 0;
    unsigned expected_r = 0;
    unsigned expected_a = 0;
  };
  std::map<std::string, RealAlphaSample> real_cef_raw_samples_;
  std::map<std::string, RealAlphaSample> real_cef_composition_samples_;
  void* last_handle_ = nullptr;
  std::uint64_t child_process_launches_ = 0;
  std::uint64_t gpu_process_launches_ = 0;
  std::uint64_t render_process_launches_ = 0;
  std::string last_gpu_command_line_;
  int actual_width_ = 0;
  int actual_height_ = 0;
  bool load_finished_ = false;
  bool load_success_ = false;
  TimingSummary raf_;
  bool d3d_attempted_ = false;
  bool d3d_opened_ = false;
  unsigned d3d_width_ = 0;
  unsigned d3d_height_ = 0;
  unsigned d3d_color_type_ = 0;
  unsigned d3d_dxgi_format_ = 0;
  unsigned d3d_mip_levels_ = 0;
  unsigned d3d_array_size_ = 0;
  unsigned d3d_sample_count_ = 0;
  unsigned d3d_usage_ = 0;
  unsigned d3d_bind_flags_ = 0;
  unsigned d3d_cpu_access_flags_ = 0;
  unsigned d3d_misc_flags_ = 0;
  long d3d_result_ = 0;
};
