#!/usr/bin/env python3
"""
V1 ODF Optimizer GUI
- 음악 파일 등록
- 파라미터 실시간 조정
- 결과 시각화 (ODF 곡선, AC/Prior/Score)
- MADMOM_GT 비교
"""

import sys
import os
import json
from pathlib import Path
from typing import Dict, List
from datetime import datetime

try:
    from PyQt6.QtWidgets import (
        QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
        QPushButton, QSlider, QLabel, QListWidget, QListWidgetItem,
        QFileDialog, QSplitter, QTabWidget, QDoubleSpinBox, QSpinBox,
        QMessageBox, QProgressBar
    )
    from PyQt6.QtCore import Qt, QThread, pyqtSignal
    from PyQt6.QtGui import QFont, QColor
    import matplotlib.pyplot as plt
    from matplotlib.backends.backend_qt5agg import FigureCanvasQTAgg as FigureCanvas
    from matplotlib.figure import Figure
except ImportError:
    print("❌ PyQt6 또는 matplotlib이 설치되지 않았습니다.")
    print("설치: pip install PyQt6 matplotlib numpy librosa")
    sys.exit(1)

from odf_engine import ODFOptimizer

# MADMOM_GT 기준값
MADMOM_GT = {
    "Golden": 122.45,
    "Soda Pop Official Lyric Video  KPop Demon Hunters  Sony Animation": 125.0,
    "Celebrity (Celebrity)": 100.0,
    "iKON - '사랑을 했다(LOVE SCENARIO)' MV": 117.65,
    "Attention": 105.26,
    "TOMBOY": 74.07,
    "Dynamite": 120.0,
    "Stray Kids 神메뉴(God's Menu) MV": 157.89,
    "Entrance": 105.26,
    "모든 날, 모든 순간 Every day, Every Moment": 66.67,
    "Let's go see the stars (별 보러 가자)": 66.67,
    "BLACKPINK - 'Kill This Love' MV": 133.33,
    "Good Goodbye": 100.0,
    "Almond Chocolate": 109.09,
    "ILLIT (아일릿) 'Cherish (My Love)' Dance Practice (Fix Ver.)": 142.86,
    "IYKYK (If You Know You Know)": 127.66,
    "Tick-Tack": 130.43,
    "I'll Like You": 130.43,
    "Pimple": 105.26,
    "ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV": 120.0,
    "Midnight Fiction": 130.43,
    "Magnetic": 130.43,
    "My World": 107.14,
    "BLACKPINK - '뛰어(JUMP)' MV": 146.34,
    "Charlie Puth - Attention [Official Video]": 100.0,
    "Ed Sheeran - Shape of You (Official Music Video)": 96.77,
    "aespa 에스파 'Supernova' MV": 120.0,
    "aespa 에스파 'Next Level' MV": 109.09,
    "LE SSERAFIM(르세라핌) 'SPAGHETTI' (4K)  STUDIO CHOOM ORIGINAL": 111.11,
    "ILLIT(아일릿) 'It's Me' (4K)  STUDIO CHOOM ORIGINAL": 146.34,
    "The_Drum_cover_by_COOMO": 127.66,
    "[클래식을 좋아하세요! CD 01] Bach 01 Brandenburg Concerto No.3 In G Majo": 100.0,
    "홍진영 - 사랑의 배터리": 130.43,
    "홍진영 - 오늘 밤에": 113.21,
    "유진표 - 천년지기": 139.53,
    "박구윤 - 뿐이고": 150.0,
    "장윤정 - 사랑 참": 142.86,
    "금잔디 - 오라버니": 139.53,
    "진미령 - 미운사랑": 69.77,
    "김연자 - 아모르 파티": 133.33,
    "장윤정 - 초혼": 72.29,
    "진성 - 안동역에서": 133.33,
}


class ProcessWorker(QThread):
    """백그라운드 처리용 스레드"""
    finished = pyqtSignal(dict)
    error = pyqtSignal(str)

    def __init__(self, optimizer, file_path, params):
        super().__init__()
        self.optimizer = optimizer
        self.file_path = file_path
        self.params = params

    def run(self):
        try:
            result = self.optimizer.optimize(self.file_path, **self.params)
            if result:
                self.finished.emit(result)
            else:
                self.error.emit("처리 실패")
        except Exception as e:
            self.error.emit(str(e))


class ODFOptimizerApp(QMainWindow):
    """메인 GUI 애플리케이션"""

    def __init__(self):
        super().__init__()
        self.setWindowTitle("V1 ODF Optimizer")
        self.setGeometry(100, 100, 1600, 900)

        self.optimizer = ODFOptimizer()
        self.files: Dict[str, str] = {}  # {filename: filepath}
        self.results = {}  # {filename: result}

        self.init_ui()

    def init_ui(self):
        """UI 초기화"""
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        layout = QHBoxLayout(central_widget)

        # 왼쪽: 파일 목록
        left_layout = QVBoxLayout()

        # 파일 추가 버튼
        add_btn = QPushButton("📁 파일 추가")
        add_btn.clicked.connect(self.add_files)
        left_layout.addWidget(add_btn)

        # 파일 목록
        self.file_list = QListWidget()
        self.file_list.itemClicked.connect(self.on_file_selected)
        left_layout.addWidget(QLabel("곡 목록:"))
        left_layout.addWidget(self.file_list)

        # 오른쪽: 파라미터 + 결과
        right_layout = QVBoxLayout()

        # 탭: 파라미터 / 결과
        tabs = QTabWidget()

        # Tab 1: 파라미터 조정
        param_widget = QWidget()
        param_layout = QVBoxLayout(param_widget)

        params_info = [
            ("Smooth Window", "smooth_window", 1, 10, 3),
            ("Local Window", "local_window", 30, 120, 60),
            ("Global Window", "global_window", 30, 120, 80),
            ("Prior Center (ms)", "prior_center_ms", 300, 800, 500),
            ("Prior STD (octave)", "prior_std_octave", 0.5, 4.0, 2.0),
            ("Min Beat (ms)", "min_beat_ms", 250, 500, 375),
            ("Max Beat (ms)", "max_beat_ms", 800, 1500, 1000),
        ]

        self.param_widgets = {}

        for label_text, param_name, min_val, max_val, default_val in params_info:
            param_layout.addWidget(QLabel(f"<b>{label_text}</b>"))

            h_layout = QHBoxLayout()

            if isinstance(default_val, float):
                spin = QDoubleSpinBox()
                spin.setRange(min_val, max_val)
                spin.setValue(default_val)
                spin.setSingleStep(0.1)
                spin.valueChanged.connect(self.on_param_changed)
            else:
                spin = QSpinBox()
                spin.setRange(min_val, max_val)
                spin.setValue(default_val)
                spin.valueChanged.connect(self.on_param_changed)

            self.param_widgets[param_name] = spin
            h_layout.addWidget(spin)
            h_layout.addWidget(QLabel(f"{spin.value()}"))

            param_layout.addLayout(h_layout)

        param_layout.addStretch()

        # 분석 버튼
        analyze_btn = QPushButton("🔄 분석 시작")
        analyze_btn.clicked.connect(self.analyze_selected)
        param_layout.addWidget(analyze_btn)

        tabs.addTab(param_widget, "파라미터")

        # Tab 2: 결과 시각화
        result_widget = QWidget()
        result_layout = QVBoxLayout(result_widget)

        self.figure = Figure(figsize=(10, 6))
        self.canvas = FigureCanvas(self.figure)
        result_layout.addWidget(self.canvas)

        # 결과 정보
        self.result_label = QLabel("선택된 곡이 없습니다")
        self.result_label.setFont(QFont("Monospace", 10))
        result_layout.addWidget(self.result_label)

        tabs.addTab(result_widget, "결과")

        # Tab 3: 배치 분석
        batch_widget = QWidget()
        batch_layout = QVBoxLayout(batch_widget)

        batch_layout.addWidget(QLabel("<b>배치 분석 - 등록된 모든 곡 분석</b>"))

        batch_btn = QPushButton("🔄 배치 분석 시작")
        batch_btn.clicked.connect(self.batch_analyze)
        batch_layout.addWidget(batch_btn)

        # 결과 테이블
        self.batch_result_label = QLabel("준비 완료")
        self.batch_result_label.setFont(QFont("Monospace", 9))
        batch_layout.addWidget(self.batch_result_label)

        # 내보내기 버튼
        export_btn = QPushButton("💾 결과 내보내기 (JSON)")
        export_btn.clicked.connect(self.export_results)
        batch_layout.addWidget(export_btn)

        tabs.addTab(batch_widget, "배치 분석")

        right_layout.addWidget(tabs)

        # 레이아웃 추가
        layout.addLayout(left_layout, 1)
        layout.addLayout(right_layout, 2)

    def add_files(self):
        """파일 추가"""
        files, _ = QFileDialog.getOpenFileNames(
            self, "음악 파일 선택", "",
            "Audio Files (*.mp3 *.wav *.flac);;All Files (*)"
        )

        for file in files:
            filename = Path(file).stem
            self.files[filename] = file
            item = QListWidgetItem(filename)
            self.file_list.addItem(item)

    def on_file_selected(self, item):
        """파일 선택"""
        filename = item.text()
        self.current_file = filename
        self.current_path = self.files[filename]

        # 결과 표시
        if filename in self.results:
            self.display_result(self.results[filename], filename)
        else:
            self.result_label.setText(f"분석 대기: {filename}")

    def on_param_changed(self):
        """파라미터 변경 시"""
        # 파라미터 값 표시 업데이트
        for param_name, widget in self.param_widgets.items():
            value = widget.value()
            # UI에 값 반영 (필요시)

    def analyze_selected(self):
        """선택된 파일 분석"""
        if not hasattr(self, 'current_file'):
            QMessageBox.warning(self, "경고", "파일을 선택하세요")
            return

        # 파라미터 수집
        params = {
            'smooth_window': int(self.param_widgets['smooth_window'].value()),
            'local_window': int(self.param_widgets['local_window'].value()),
            'global_window': int(self.param_widgets['global_window'].value()),
            'prior_center_ms': int(self.param_widgets['prior_center_ms'].value()),
            'prior_std_octave': float(self.param_widgets['prior_std_octave'].value()),
            'min_beat_ms': int(self.param_widgets['min_beat_ms'].value()),
            'max_beat_ms': int(self.param_widgets['max_beat_ms'].value()),
        }

        # 백그라운드 처리
        self.worker = ProcessWorker(self.optimizer, self.current_path, params)
        self.worker.finished.connect(self.on_analysis_finished)
        self.worker.error.connect(self.on_analysis_error)
        self.worker.start()

        self.result_label.setText(f"분석 중... {self.current_file}")

    def on_analysis_finished(self, result):
        """분석 완료"""
        self.results[self.current_file] = result
        self.display_result(result, self.current_file)

    def on_analysis_error(self, error):
        """분석 오류"""
        QMessageBox.critical(self, "오류", f"분석 실패: {error}")
        self.result_label.setText("분석 실패")

    def display_result(self, result, filename):
        """결과 시각화"""
        if not result:
            return

        detected_bpm = result['bpm']
        odf = result['odf']
        ac_vals = result['ac_vals']
        prior_vals = result['prior_vals']
        score_vals = result['score_vals']

        # MADMOM_GT와 비교
        gt_bpm = MADMOM_GT.get(filename, None)
        if gt_bpm:
            error_pct = abs(detected_bpm - gt_bpm) / gt_bpm * 100
            status = "✅" if error_pct <= 1 else "❌"
            result_text = f"{status} {filename}\nGT: {gt_bpm:.2f} BPM\n검출: {detected_bpm:.1f} BPM\n오차: {error_pct:.1f}%"
        else:
            result_text = f"{filename}\n검출 BPM: {detected_bpm:.1f}"

        self.result_label.setText(result_text)

        # 그래프
        self.figure.clear()

        # ODF 곡선
        ax1 = self.figure.add_subplot(211)
        ax1.plot(odf, linewidth=1, color='blue', label='ODF')
        ax1.set_title("ODF (Onset Detection Function)")
        ax1.set_xlabel("Frame")
        ax1.set_ylabel("Magnitude")
        ax1.grid(True, alpha=0.3)
        ax1.legend()

        # AC/Prior/Score 곡선
        ax2 = self.figure.add_subplot(212)
        if ac_vals is not None:
            lag_range = range(len(ac_vals))
            ax2.plot(lag_range, ac_vals, label='AC', alpha=0.7)
            ax2.plot(lag_range, prior_vals, label='Prior', alpha=0.7)
            ax2.plot(lag_range, score_vals, label='Score', alpha=0.7, linewidth=2)
            ax2.axvline(int(60000 / (detected_bpm * 50)), color='red', linestyle='--', label=f'Detected: {detected_bpm:.1f} BPM')
            ax2.set_title("Autocorrelation / Prior / Score")
            ax2.set_xlabel("Lag")
            ax2.set_ylabel("Value")
            ax2.grid(True, alpha=0.3)
            ax2.legend()

        self.figure.tight_layout()
        self.canvas.draw()

    def batch_analyze(self):
        """등록된 모든 곡 배치 분석"""
        if not self.files:
            QMessageBox.warning(self, "경고", "파일을 추가해주세요")
            return

        # 파라미터 수집
        params = {
            'smooth_window': int(self.param_widgets['smooth_window'].value()),
            'local_window': int(self.param_widgets['local_window'].value()),
            'global_window': int(self.param_widgets['global_window'].value()),
            'prior_center_ms': int(self.param_widgets['prior_center_ms'].value()),
            'prior_std_octave': float(self.param_widgets['prior_std_octave'].value()),
            'min_beat_ms': int(self.param_widgets['min_beat_ms'].value()),
            'max_beat_ms': int(self.param_widgets['max_beat_ms'].value()),
        }

        self.batch_result_label.setText("배치 분석 중...\n")

        # 각 파일 분석
        summary = "곡\tGT BPM\t검출 BPM\t오차율\t정확도\n"
        summary += "-" * 70 + "\n"

        correct_count = 0
        total_count = 0

        for filename, filepath in self.files.items():
            self.worker = ProcessWorker(self.optimizer, filepath, params)
            self.worker.finished.connect(lambda result, fn=filename: None)

            # 동기적으로 처리
            try:
                result = self.optimizer.optimize(filepath, **params)
                if result:
                    self.results[filename] = result
                    detected_bpm = result['bpm']

                    gt_bpm = MADMOM_GT.get(filename, None)
                    if gt_bpm:
                        error_pct = abs(detected_bpm - gt_bpm) / gt_bpm * 100
                        is_correct = error_pct <= 1
                        if is_correct:
                            correct_count += 1
                            status = "✅"
                        else:
                            status = "❌"

                        summary += f"{filename[:20]:<20}\t{gt_bpm:.1f}\t{detected_bpm:.1f}\t{error_pct:.1f}%\t{status}\n"
                        total_count += 1
                    else:
                        summary += f"{filename[:20]:<20}\t-\t{detected_bpm:.1f}\t-\t-\n"
            except Exception as e:
                summary += f"{filename[:20]:<20}\t오류: {str(e)[:20]}\n"

        # 결과 요약
        if total_count > 0:
            accuracy = correct_count / total_count * 100
            summary += f"\n정확도: {correct_count}/{total_count} = {accuracy:.1f}%\n"

        self.batch_result_label.setText(summary)

    def export_results(self):
        """결과를 JSON으로 내보내기"""
        if not self.results:
            QMessageBox.warning(self, "경고", "분석 결과가 없습니다")
            return

        filepath, _ = QFileDialog.getSaveFileName(
            self, "결과 저장", "",
            "JSON Files (*.json);;All Files (*)"
        )

        if not filepath:
            return

        try:
            # 결과를 JSON 직렬화 가능한 형태로 변환
            export_data = {
                'timestamp': datetime.now().isoformat(),
                'parameters': {
                    'smooth_window': int(self.param_widgets['smooth_window'].value()),
                    'local_window': int(self.param_widgets['local_window'].value()),
                    'global_window': int(self.param_widgets['global_window'].value()),
                    'prior_center_ms': int(self.param_widgets['prior_center_ms'].value()),
                    'prior_std_octave': float(self.param_widgets['prior_std_octave'].value()),
                    'min_beat_ms': int(self.param_widgets['min_beat_ms'].value()),
                    'max_beat_ms': int(self.param_widgets['max_beat_ms'].value()),
                },
                'results': {}
            }

            for filename, result in self.results.items():
                if result:
                    gt_bpm = MADMOM_GT.get(filename, None)
                    detected_bpm = result['bpm']

                    export_data['results'][filename] = {
                        'detected_bpm': float(detected_bpm),
                        'gt_bpm': float(gt_bpm) if gt_bpm else None,
                        'error_pct': float(abs(detected_bpm - gt_bpm) / gt_bpm * 100) if gt_bpm else None,
                        'duration_s': float(result['duration_s']),
                    }

            with open(filepath, 'w', encoding='utf-8') as f:
                json.dump(export_data, f, indent=2, ensure_ascii=False)

            QMessageBox.information(self, "성공", f"결과가 저장되었습니다:\n{filepath}")
        except Exception as e:
            QMessageBox.critical(self, "오류", f"저장 실패: {str(e)}")


def main():
    app = QApplication(sys.argv)
    window = ODFOptimizerApp()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
