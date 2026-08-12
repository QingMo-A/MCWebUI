#pragma once

#include <chrono>
#include <cstdint>
#include <mutex>
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
               int configured_height, bool accelerated, bool animate);
  void RecordFrameRequest();
  void RecordPaint(int width, int height, std::size_t dirty_rect_count);
  void RecordAcceleratedPaint(std::size_t dirty_rect_count, void* handle);
  void RecordD3D(bool opened, unsigned width, unsigned height, unsigned format,
                 unsigned sample_count, long result);
  void RecordRaf(std::uint64_t callbacks, double rate_hz, double median_ms,
                 double p95_ms, double max_ms);
  void RecordLoad(bool success);
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
  const Clock::time_point started_ = Clock::now();
  mutable std::mutex mutex_;
  std::vector<Clock::time_point> requests_;
  std::vector<Clock::time_point> paints_;
  std::vector<Clock::time_point> accelerated_paints_;
  std::uint64_t dirty_rects_ = 0;
  std::uint64_t estimated_bytes_ = 0;
  std::uint64_t accelerated_dirty_rects_ = 0;
  std::uint64_t handle_changes_ = 0;
  void* last_handle_ = nullptr;
  int actual_width_ = 0;
  int actual_height_ = 0;
  bool load_finished_ = false;
  bool load_success_ = false;
  TimingSummary raf_;
  bool d3d_attempted_ = false;
  bool d3d_opened_ = false;
  unsigned d3d_width_ = 0;
  unsigned d3d_height_ = 0;
  unsigned d3d_format_ = 0;
  unsigned d3d_sample_count_ = 0;
  long d3d_result_ = 0;
};
