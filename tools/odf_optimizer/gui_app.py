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
        QMessageBox, QProgressBar, QComboBox, QCheckBox
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

        # 파일 목록 저장 경로
        self.config_dir = Path(__file__).parent
        self.file_list_path = self.config_dir / "file_list.json"

        self.init_ui()
        self.load_file_list()

    def init_ui(self):
        """UI 초기화"""
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        layout = QHBoxLayout(central_widget)

        # 왼쪽: 파일 목록
        left_layout = QVBoxLayout()

        # 파일 추가/삭제 버튼
        btn_layout = QHBoxLayout()
        add_btn = QPushButton("📁 파일 추가")
        add_btn.clicked.connect(self.add_files)
        btn_layout.addWidget(add_btn)

        remove_btn = QPushButton("🗑️ 선택 삭제")
        remove_btn.clicked.connect(self.remove_selected_file)
        btn_layout.addWidget(remove_btn)

        clear_btn = QPushButton("🔄 모두 지우기")
        clear_btn.clicked.connect(self.clear_all_files)
        btn_layout.addWidget(clear_btn)

        left_layout.addLayout(btn_layout)

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
            ("Smooth Window", "smooth_window", 1, 10, 3,
             "ODF 노이즈 제거 (증가 = 부드러움 ↑, 감소 = 민감도 ↑)\n"
             "기본값 3이 균형잡힘. 느린곡은 5-7, 빠른곡은 1-2"),

            ("Local Window", "local_window", 30, 120, 60,
             "온셋 구분 윈도우 (프레임)\n"
             "빠른곡 40-50, 기본 60, 느린곡 80-100"),

            ("Global Window", "global_window", 30, 120, 80,
             "배경음 제거 윈도우 (프레임)\n"
             "증가 = 배경 제거 강화, 감소 = 신호 보존"),

            ("Prior Center (ms)", "prior_center_ms", 300, 800, 500,
             "선호 BPM 중심값\n"
             "300ms→200BPM, 500ms→120BPM(기본), 700ms→86BPM"),

            ("Prior STD (octave)", "prior_std_octave", 0.5, 4.0, 2.0,
             "BPM 분포 범위 (옥타브)\n"
             "1.0=좁음(선택적), 2.0=중간(권장), 3.0+=넓음(포용적)"),

            ("Min Beat (ms)", "min_beat_ms", 250, 500, 375,
             "최소 비트 간격 = 최대 BPM\n"
             "375ms→160BPM(기본), 감소→빠른곡 대응"),

            ("Max Beat (ms)", "max_beat_ms", 800, 1500, 1000,
             "최대 비트 간격 = 최소 BPM\n"
             "1000ms→60BPM(기본), 증가→느린곡 대응"),
        ]

        self.param_widgets = {}

        for label_text, param_name, min_val, max_val, default_val, tooltip_text in params_info:
            label = QLabel(f"<b>{label_text}</b>")
            label.setToolTip(tooltip_text)
            param_layout.addWidget(label)

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

            spin.setToolTip(tooltip_text)
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

        # Tab 3: 신호 처리 (음성 전처리)
        signal_widget = QWidget()
        signal_layout = QVBoxLayout(signal_widget)

        signal_layout.addWidget(QLabel("<b>🎙️ 음성 전처리 설정</b>"))

        # 샘플레이트
        signal_layout.addWidget(QLabel("<b>Sample Rate (Hz)</b>"))
        self.sr_combo = QComboBox()
        self.sr_combo.addItems(["16000", "22050", "28000", "44100"])
        self.sr_combo.setCurrentText("28000")
        signal_layout.addWidget(self.sr_combo)

        # Normalization
        signal_layout.addWidget(QLabel("<b>Normalization (0.0-2.0)</b>"))
        self.norm_spin = QDoubleSpinBox()
        self.norm_spin.setRange(0.0, 2.0)
        self.norm_spin.setValue(1.0)
        self.norm_spin.setSingleStep(0.1)
        self.norm_spin.setToolTip("음성의 크기 정규화 (0=안함, 1=기본, 2=강함)")
        signal_layout.addWidget(self.norm_spin)

        # Pre-emphasis
        signal_layout.addWidget(QLabel("<b>Pre-emphasis (0.0-1.0)</b>"))
        self.preemph_spin = QDoubleSpinBox()
        self.preemph_spin.setRange(0.0, 1.0)
        self.preemph_spin.setValue(0.0)
        self.preemph_spin.setSingleStep(0.05)
        self.preemph_spin.setToolTip("고주파 강화 필터 (0=안함, 0.97=표준)")
        signal_layout.addWidget(self.preemph_spin)

        # Compression
        signal_layout.addWidget(QLabel("<b>Compression Ratio (1.0-10.0)</b>"))
        self.compress_spin = QDoubleSpinBox()
        self.compress_spin.setRange(1.0, 10.0)
        self.compress_spin.setValue(1.0)
        self.compress_spin.setSingleStep(0.5)
        self.compress_spin.setToolTip("동적 범위 압축 (1=안함, 4=강함)")
        signal_layout.addWidget(self.compress_spin)

        # Bandpass Filter
        signal_layout.addWidget(QLabel("<b>Bandpass Filter</b>"))
        self.bandpass_check = QCheckBox("활성화")
        signal_layout.addWidget(self.bandpass_check)

        signal_layout.addWidget(QLabel("Low Freq (Hz)"))
        self.bandpass_low_spin = QSpinBox()
        self.bandpass_low_spin.setRange(0, 500)
        self.bandpass_low_spin.setValue(50)
        signal_layout.addWidget(self.bandpass_low_spin)

        signal_layout.addWidget(QLabel("High Freq (Hz)"))
        self.bandpass_high_spin = QSpinBox()
        self.bandpass_high_spin.setRange(1000, 20000)
        self.bandpass_high_spin.setValue(8000)
        signal_layout.addWidget(self.bandpass_high_spin)

        # IIR 필터 계수
        signal_layout.addWidget(QLabel("<b>🎚️ IIR 필터 계수</b>"))

        signal_layout.addWidget(QLabel("LOW_ALPHA (0.05-0.5)"))
        self.low_alpha_spin = QDoubleSpinBox()
        self.low_alpha_spin.setRange(0.05, 0.5)
        self.low_alpha_spin.setValue(0.12)
        self.low_alpha_spin.setSingleStep(0.01)
        self.low_alpha_spin.setToolTip("저역 필터 계수 (작을수록 부드러움)")
        signal_layout.addWidget(self.low_alpha_spin)

        signal_layout.addWidget(QLabel("MID_LP1_ALPHA (0.1-0.9)"))
        self.mid_lp1_spin = QDoubleSpinBox()
        self.mid_lp1_spin.setRange(0.1, 0.9)
        self.mid_lp1_spin.setValue(0.35)
        self.mid_lp1_spin.setSingleStep(0.05)
        signal_layout.addWidget(self.mid_lp1_spin)

        signal_layout.addWidget(QLabel("MID_LP2_ALPHA (0.01-0.3)"))
        self.mid_lp2_spin = QDoubleSpinBox()
        self.mid_lp2_spin.setRange(0.01, 0.3)
        self.mid_lp2_spin.setValue(0.08)
        self.mid_lp2_spin.setSingleStep(0.01)
        signal_layout.addWidget(self.mid_lp2_spin)

        # ODF 대역 가중치
        signal_layout.addWidget(QLabel("<b>📊 ODF 대역 가중치</b>"))

        signal_layout.addWidget(QLabel("Weight Low (0.0-3.0)"))
        self.weight_low_spin = QDoubleSpinBox()
        self.weight_low_spin.setRange(0.0, 3.0)
        self.weight_low_spin.setValue(1.0)
        self.weight_low_spin.setSingleStep(0.1)
        self.weight_low_spin.setToolTip("저역 가중치 (기본: 1.0)")
        signal_layout.addWidget(self.weight_low_spin)

        signal_layout.addWidget(QLabel("Weight Mid (0.0-3.0)"))
        self.weight_mid_spin = QDoubleSpinBox()
        self.weight_mid_spin.setRange(0.0, 3.0)
        self.weight_mid_spin.setValue(1.8)
        self.weight_mid_spin.setSingleStep(0.1)
        self.weight_mid_spin.setToolTip("중역 가중치 (기본: 1.8)")
        signal_layout.addWidget(self.weight_mid_spin)

        signal_layout.addWidget(QLabel("Weight Full (0.0-3.0)"))
        self.weight_full_spin = QDoubleSpinBox()
        self.weight_full_spin.setRange(0.0, 3.0)
        self.weight_full_spin.setValue(0.8)
        self.weight_full_spin.setSingleStep(0.1)
        self.weight_full_spin.setToolTip("전역 가중치 (기본: 0.8)")
        signal_layout.addWidget(self.weight_full_spin)

        signal_layout.addStretch()
        tabs.addTab(signal_widget, "신호 처리")

        # Tab 4: 배치 분석
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

    def load_file_list(self):
        """저장된 파일 목록 로드"""
        try:
            if self.file_list_path.exists():
                with open(self.file_list_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    for filename, filepath in data.items():
                        if Path(filepath).exists():
                            self.files[filename] = filepath
                            item = QListWidgetItem(filename)
                            self.file_list.addItem(item)
        except Exception as e:
            print(f"파일 목록 로드 실패: {e}")

    def save_file_list(self):
        """파일 목록 저장"""
        try:
            with open(self.file_list_path, 'w', encoding='utf-8') as f:
                json.dump(self.files, f, ensure_ascii=False, indent=2)
        except Exception as e:
            print(f"파일 목록 저장 실패: {e}")

    def add_files(self):
        """파일 추가"""
        files, _ = QFileDialog.getOpenFileNames(
            self, "음악 파일 선택", "",
            "Audio Files (*.mp3 *.wav *.flac);;All Files (*)"
        )

        for file in files:
            filename = Path(file).stem
            if filename not in self.files:
                self.files[filename] = file
                item = QListWidgetItem(filename)
                self.file_list.addItem(item)

        self.save_file_list()

    def remove_selected_file(self):
        """선택된 파일 삭제"""
        current_item = self.file_list.currentItem()
        if current_item:
            filename = current_item.text()
            if filename in self.files:
                del self.files[filename]
                self.file_list.takeItem(self.file_list.row(current_item))
                if filename in self.results:
                    del self.results[filename]
                self.result_label.setText("파일이 삭제되었습니다")
                self.save_file_list()
        else:
            QMessageBox.warning(self, "경고", "삭제할 파일을 선택하세요")

    def clear_all_files(self):
        """모든 파일 삭제"""
        reply = QMessageBox.question(self, "확인", "모든 파일을 삭제하시겠습니까?",
                                     QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
        if reply == QMessageBox.StandardButton.Yes:
            self.files.clear()
            self.results.clear()
            self.file_list.clear()
            self.result_label.setText("모든 파일이 삭제되었습니다")
            self.save_file_list()

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

        # 파라미터 수집 (ODF 계산 파라미터)
        params = {
            'smooth_window': int(self.param_widgets['smooth_window'].value()),
            'local_window': int(self.param_widgets['local_window'].value()),
            'global_window': int(self.param_widgets['global_window'].value()),
            'prior_center_ms': int(self.param_widgets['prior_center_ms'].value()),
            'prior_std_octave': float(self.param_widgets['prior_std_octave'].value()),
            'min_beat_ms': int(self.param_widgets['min_beat_ms'].value()),
            'max_beat_ms': int(self.param_widgets['max_beat_ms'].value()),
            # 음성 전처리 파라미터
            'sample_rate': int(self.sr_combo.currentText()),
            'normalize_strength': float(self.norm_spin.value()),
            'pre_emphasis': float(self.preemph_spin.value()),
            'compress_ratio': float(self.compress_spin.value()),
            'enable_bandpass': self.bandpass_check.isChecked(),
            'bandpass_low': float(self.bandpass_low_spin.value()),
            'bandpass_high': float(self.bandpass_high_spin.value()),
            # IIR 필터 계수
            'low_alpha': float(self.low_alpha_spin.value()),
            'mid_lp1_alpha': float(self.mid_lp1_spin.value()),
            'mid_lp2_alpha': float(self.mid_lp2_spin.value()),
            # ODF 대역 가중치
            'weight_low': float(self.weight_low_spin.value()),
            'weight_mid': float(self.weight_mid_spin.value()),
            'weight_full': float(self.weight_full_spin.value()),
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
        """등록된 모든 곡 배치 분석 (실시간 결과 표시)"""
        if not self.files:
            QMessageBox.warning(self, "경고", "파일을 추가해주세요")
            return

        # 파라미터 수집 (ODF 파라미터 + 신호 처리 파라미터)
        params = {
            'smooth_window': int(self.param_widgets['smooth_window'].value()),
            'local_window': int(self.param_widgets['local_window'].value()),
            'global_window': int(self.param_widgets['global_window'].value()),
            'prior_center_ms': int(self.param_widgets['prior_center_ms'].value()),
            'prior_std_octave': float(self.param_widgets['prior_std_octave'].value()),
            'min_beat_ms': int(self.param_widgets['min_beat_ms'].value()),
            'max_beat_ms': int(self.param_widgets['max_beat_ms'].value()),
            # 신호 처리 파라미터
            'sample_rate': int(self.sr_combo.currentText()),
            'normalize_strength': float(self.norm_spin.value()),
            'pre_emphasis': float(self.preemph_spin.value()),
            'compress_ratio': float(self.compress_spin.value()),
            'enable_bandpass': self.bandpass_check.isChecked(),
            'bandpass_low': float(self.bandpass_low_spin.value()),
            'bandpass_high': float(self.bandpass_high_spin.value()),
            # IIR 필터 계수
            'low_alpha': float(self.low_alpha_spin.value()),
            'mid_lp1_alpha': float(self.mid_lp1_spin.value()),
            'mid_lp2_alpha': float(self.mid_lp2_spin.value()),
            # ODF 대역 가중치
            'weight_low': float(self.weight_low_spin.value()),
            'weight_mid': float(self.weight_mid_spin.value()),
            'weight_full': float(self.weight_full_spin.value()),
        }

        # 헤더 표시
        total_files = len(self.files)
        summary = f"🔄 배치 분석 진행 중... (0/{total_files})\n"
        summary += "곡\t\t\t\tGT BPM\t검출 BPM\t오차율\t정확도\n"
        summary += "-" * 90 + "\n"
        self.batch_result_label.setText(summary)

        correct_count = 0
        total_count = 0
        completed = 0

        # 각 파일 순차 분석
        for idx, (filename, filepath) in enumerate(self.files.items(), 1):
            try:
                # 동기적으로 처리
                result = self.optimizer.optimize(filepath, **params)
                if result:
                    self.results[filename] = result
                    detected_bpm = result['bpm']

                    gt_bpm = MADMOM_GT.get(filename, None)

                    # 결과 생성
                    if gt_bpm:
                        error_pct = abs(detected_bpm - gt_bpm) / gt_bpm * 100
                        is_correct = error_pct <= 1
                        if is_correct:
                            correct_count += 1
                            status = "✅"
                        else:
                            status = "❌"

                        result_line = f"{filename[:30]:<30}\t{gt_bpm:.1f}\t{detected_bpm:.1f}\t{error_pct:>6.1f}%\t{status}\n"
                        total_count += 1
                    else:
                        result_line = f"{filename[:30]:<30}\t-\t{detected_bpm:.1f}\t-\t-\n"
                else:
                    result_line = f"{filename[:30]:<30}\t오류\t-\t-\t❌\n"

            except Exception as e:
                result_line = f"{filename[:30]:<30}\t오류: {str(e)[:15]}\n"

            completed += 1

            # 실시간 업데이트 (각 곡마다)
            header = f"🔄 배치 분석 진행 중... ({completed}/{total_files})\n"
            header += "곡\t\t\t\tGT BPM\t검출 BPM\t오차율\t정확도\n"
            header += "-" * 90 + "\n"

            # 이전 결과들 + 새로운 결과
            current_summary = summary.replace(
                f"🔄 배치 분석 진행 중... (0/{total_files})\n",
                header
            )
            current_summary = header

            # 지금까지의 모든 결과 재구성
            for idx2, (fn, fp) in enumerate(list(self.files.items())[:completed]):
                if idx2 < completed - 1:
                    # 이미 완료된 곡들
                    if fn in self.results:
                        res = self.results[fn]
                        det_bpm = res['bpm']
                        gt = MADMOM_GT.get(fn, None)
                        if gt:
                            err = abs(det_bpm - gt) / gt * 100
                            st = "✅" if err <= 1 else "❌"
                            current_summary += f"{fn[:30]:<30}\t{gt:.1f}\t{det_bpm:.1f}\t{err:>6.1f}%\t{st}\n"
                        else:
                            current_summary += f"{fn[:30]:<30}\t-\t{det_bpm:.1f}\t-\t-\n"
                else:
                    # 현재 완료된 곡
                    current_summary += result_line

            # 진행률 추가
            if completed < total_files:
                current_summary += f"\n⏳ 분석 중... ({completed}/{total_files})"
                if total_count > 0:
                    current_accuracy = correct_count / total_count * 100
                    current_summary += f" | 현재 정확도: {correct_count}/{total_count} = {current_accuracy:.1f}%"

            self.batch_result_label.setText(current_summary)

            # UI 업데이트
            __import__('PyQt6.QtWidgets').QApplication.processEvents()

        # 최종 결과 요약
        final_summary = f"✅ 배치 분석 완료! ({total_files}/{total_files})\n"
        final_summary += "곡\t\t\t\tGT BPM\t검출 BPM\t오차율\t정확도\n"
        final_summary += "-" * 90 + "\n"

        for filename, filepath in self.files.items():
            if filename in self.results:
                res = self.results[filename]
                det_bpm = res['bpm']
                gt = MADMOM_GT.get(filename, None)
                if gt:
                    err = abs(det_bpm - gt) / gt * 100
                    st = "✅" if err <= 1 else "❌"
                    final_summary += f"{filename[:30]:<30}\t{gt:.1f}\t{det_bpm:.1f}\t{err:>6.1f}%\t{st}\n"
                else:
                    final_summary += f"{filename[:30]:<30}\t-\t{det_bpm:.1f}\t-\t-\n"

        # 정확도 요약
        if total_count > 0:
            accuracy = correct_count / total_count * 100
            final_summary += "\n" + "=" * 90 + "\n"
            final_summary += f"📊 최종 정확도: {correct_count}/{total_count} = {accuracy:.1f}%\n"
            final_summary += f"정확 곡: {correct_count}곡, 부정확 곡: {total_count - correct_count}곡\n"

        self.batch_result_label.setText(final_summary)

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

    def closeEvent(self, event):
        """애플리케이션 종료 시 파일 목록 저장"""
        self.save_file_list()
        event.accept()


def main():
    app = QApplication(sys.argv)
    window = ODFOptimizerApp()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
