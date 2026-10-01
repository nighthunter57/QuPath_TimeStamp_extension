import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from scripts import prepare_doctor_models as models
from scripts.prepare_doctor_models import MODEL_FILES, model_files_present, prepare_model


class DoctorModelSetupTest(unittest.TestCase):
    def write_model(self, root: Path, final: bool = False) -> None:
        for name in (*MODEL_FILES, "vocabulary.txt"):
            (root / name).write_bytes(b"cached fixture")
        if final:
            (root / "preprocessor_config.json").write_text("{}", encoding="utf-8")

    def test_complete_cache_never_requests_network(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_model(root)
            download = Mock(return_value=directory)
            self.assertEqual(root, prepare_model("Systran/faster-whisper-small.en", download))
            self.assertEqual(1, download.call_count)
            self.assertTrue(download.call_args.kwargs["local_files_only"])

    def test_partial_cache_is_repaired_and_download_is_filtered(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            requests = []
            def download(repository, **kwargs):
                requests.append(kwargs)
                if not kwargs.get("local_files_only"):
                    self.write_model(root, final=True)
                return directory
            prepare_model("Systran/faster-whisper-large-v3", download)
            self.assertEqual(2, len(requests))
            self.assertIn("model.bin", requests[1]["allow_patterns"])
            self.assertNotIn("README.md", requests[1]["allow_patterns"])

    def test_offline_missing_cache_and_incomplete_download_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            download = Mock(return_value=directory)
            with self.assertRaises(RuntimeError):
                prepare_model("Systran/faster-whisper-small.en", download, offline=True)
            self.assertEqual(1, download.call_count)
            with self.assertRaises(RuntimeError):
                prepare_model("Systran/faster-whisper-small.en", download)

    def test_final_requires_preprocessor_and_empty_weights_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_model(root)
            self.assertFalse(model_files_present(root, "Systran/faster-whisper-large-v3"))
            self.write_model(root, final=True)
            self.assertTrue(model_files_present(root, "Systran/faster-whisper-large-v3"))
            (root / "model.bin").write_bytes(b"")
            self.assertFalse(model_files_present(root, "Systran/faster-whisper-large-v3"))

    def test_hub_server_notices_are_hidden_but_errors_remain(self):
        import logging
        from huggingface_hub.utils import logging as hub_logging
        models.quiet_hub_notices()
        logger = hub_logging.get_logger("huggingface_hub.utils._http")
        self.assertFalse(logger.isEnabledFor(logging.WARNING))
        self.assertTrue(logger.isEnabledFor(logging.ERROR))

    def test_installed_decoder_reads_a_saved_wav(self):
        # Guards the installed audio stack: PyAV 19 broke faster-whisper's file decoding.
        from faster_whisper.audio import decode_audio
        with tempfile.TemporaryDirectory() as directory:
            sample = models.write_silent_wav(Path(directory) / "check.wav", seconds=0.5)
            audio = decode_audio(str(sample), sampling_rate=models.VALIDATION_SAMPLE_RATE)
        self.assertEqual(int(models.VALIDATION_SAMPLE_RATE * 0.5), len(audio))

