import argparse
import hashlib
import json
import os
import pickle
import shutil
import zipfile
from datetime import datetime, timezone

import torch
import torch.nn as nn
import torch.nn.functional as F


def _resolve_path(base_dir: str, path: str) -> str:
    if os.path.isabs(path):
        return path
    return os.path.normpath(os.path.join(base_dir, path))


class Attention(nn.Module):
    def __init__(self, hid_dim: int):
        super().__init__()
        self.attn = nn.Linear((hid_dim * 2) + hid_dim, hid_dim)
        self.v = nn.Linear(hid_dim, 1, bias=False)

    def forward(self, hidden, encoder_outputs):
        src_len = encoder_outputs.shape[0]
        hidden = hidden.unsqueeze(1).repeat(1, src_len, 1)
        encoder_outputs = encoder_outputs.permute(1, 0, 2)
        energy = torch.tanh(self.attn(torch.cat((hidden, encoder_outputs), dim=2)))
        attention = self.v(energy).squeeze(2)
        return F.softmax(attention, dim=1)


class EncoderNoPack(nn.Module):
    def __init__(self, input_dim: int, emb_dim: int, hid_dim: int, n_layers: int, dropout: float):
        super().__init__()
        self.embedding = nn.Embedding(input_dim, emb_dim)
        self.rnn = nn.GRU(emb_dim, hid_dim, n_layers, bidirectional=True, dropout=dropout)
        self.fc = nn.Linear(hid_dim * 2, hid_dim)
        self.dropout = nn.Dropout(dropout)

    def forward(self, src, src_len):
        embedded = self.dropout(self.embedding(src))
        outputs, hidden = self.rnn(embedded)
        hidden = torch.tanh(self.fc(torch.cat((hidden[-2, :, :], hidden[-1, :, :]), dim=1)))
        return outputs, hidden


class Decoder(nn.Module):
    def __init__(self, output_dim: int, emb_dim: int, hid_dim: int, n_layers: int, dropout: float, attention: Attention):
        super().__init__()
        self.output_dim = output_dim
        self.attention = attention
        self.embedding = nn.Embedding(output_dim, emb_dim)
        self.rnn = nn.GRU((hid_dim * 2) + emb_dim, hid_dim, n_layers, dropout=dropout)
        self.fc_out = nn.Linear((hid_dim * 2) + hid_dim + emb_dim, output_dim)
        self.dropout = nn.Dropout(dropout)

    def forward(self, input, hidden, encoder_outputs):
        input = input.unsqueeze(0)
        embedded = self.dropout(self.embedding(input))
        a = self.attention(hidden, encoder_outputs).unsqueeze(1)
        encoder_outputs = encoder_outputs.permute(1, 0, 2)
        weighted = torch.bmm(a, encoder_outputs).permute(1, 0, 2)
        rnn_input = torch.cat((embedded, weighted), dim=2)
        output, hidden = self.rnn(rnn_input, hidden.unsqueeze(0))
        embedded = embedded.squeeze(0)
        output = output.squeeze(0)
        weighted = weighted.squeeze(0)
        prediction = self.fc_out(torch.cat((output, weighted, embedded), dim=1))
        return prediction, hidden.squeeze(0)


class Seq2Seq(nn.Module):
    def __init__(self, encoder: nn.Module, decoder: nn.Module, device):
        super().__init__()
        self.encoder = encoder
        self.decoder = decoder
        self.device = device


def _invert_vocab(zh_vocab: dict) -> dict:
    inv = {}
    for k, v in zh_vocab.items():
        inv[v] = k
    return inv


def export_vocabs(ko_vocab: dict, zh_vocab: dict, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    ko_path = os.path.join(out_dir, "ko_vocab.json")
    zh_path = os.path.join(out_dir, "zh_ivocab.json")
    with open(ko_path, "w", encoding="utf-8") as f:
        json.dump(ko_vocab, f, ensure_ascii=False)
    inv = _invert_vocab(zh_vocab)
    max_id = max(inv.keys()) if inv else -1
    ivocab = [""] * (max_id + 1)
    for i, tok in inv.items():
        if 0 <= i <= max_id:
            ivocab[i] = tok
    with open(zh_path, "w", encoding="utf-8") as f:
        json.dump(ivocab, f, ensure_ascii=False)
    return ko_path, zh_path


def export_config(ko_vocab: dict, zh_vocab: dict, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    config_path = os.path.join(out_dir, "config.json")
    cfg = {
        "ko": {
            "sos_id": int(ko_vocab.get("<sos>", 0)),
            "eos_id": int(ko_vocab.get("<eos>", 0)),
            "unk_id": int(ko_vocab.get("<unk>", 0)),
        },
        "zh": {
            "sos_id": int(zh_vocab.get("<sos>", 0)),
            "eos_id": int(zh_vocab.get("<eos>", 0)),
            "unk_id": int(zh_vocab.get("<unk>", 0)),
            "pad_id": int(zh_vocab.get("<pad>", 0)),
        },
        "model": {
            "enc_emb_dim": 256,
            "dec_emb_dim": 256,
            "hid_dim": 512,
            "n_layers": 1,
            "dropout": 0.0,
        },
        "decode": {
            "max_len_token": 20,
            "repeat_penalty": 1.2,
        },
    }
    with open(config_path, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    return config_path


def export_torchscript(model_path: str, ko_vocab: dict, zh_vocab: dict, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    device = torch.device("cpu")
    input_dim = len(ko_vocab)
    output_dim = len(zh_vocab)
    enc_emb_dim = 256
    dec_emb_dim = 256
    hid_dim = 512
    n_layers = 1
    dropout = 0.0

    attn = Attention(hid_dim)
    enc = EncoderNoPack(input_dim, enc_emb_dim, hid_dim, n_layers, dropout)
    dec = Decoder(output_dim, dec_emb_dim, hid_dim, n_layers, dropout, attn)
    model = Seq2Seq(enc, dec, device).to(device)

    state = torch.load(model_path, map_location=device)
    model.load_state_dict(state, strict=True)
    model.eval()

    ex_src = torch.LongTensor([0, 0, 0]).unsqueeze(1)
    ex_src_len = torch.LongTensor([3])
    ex_inp = torch.LongTensor([0])

    traced_enc = torch.jit.trace(model.encoder, (ex_src, ex_src_len))
    traced_enc.eval()
    enc_out, enc_hidden = traced_enc(ex_src, ex_src_len)
    traced_dec = torch.jit.trace(model.decoder, (ex_inp, enc_hidden, enc_out))
    traced_dec.eval()

    enc_file = os.path.join(out_dir, "encoder.ptl")
    dec_file = os.path.join(out_dir, "decoder.ptl")

    def _ensure_archive_prefix(ptl_path: str) -> None:
        with zipfile.ZipFile(ptl_path) as zf:
            names = zf.namelist()

        has_archive = any(n.startswith("archive/") for n in names)
        if has_archive and "archive/bytecode.pkl" in names:
            return

        prefixes = set()
        has_root = False
        for n in names:
            if "/" not in n:
                has_root = True
                continue
            prefixes.add(n.split("/", 1)[0])

        strip_one = None
        if not has_root and len(prefixes) == 1:
            strip_one = next(iter(prefixes))

        tmp_path = ptl_path + ".tmpzip"
        with zipfile.ZipFile(ptl_path, "r") as zin, zipfile.ZipFile(tmp_path, "w") as zout:
            for info in zin.infolist():
                src_name = info.filename
                if strip_one is not None:
                    if not src_name.startswith(strip_one + "/"):
                        continue
                    rel = src_name[len(strip_one) + 1 :]
                else:
                    rel = src_name
                if not rel:
                    continue
                dst_name = rel if rel.startswith("archive/") else ("archive/" + rel)
                zi = zipfile.ZipInfo(dst_name, date_time=info.date_time)
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                zi.create_system = info.create_system
                with zin.open(info, "r") as src, zout.open(zi, "w") as dst:
                    shutil.copyfileobj(src, dst, length=1024 * 1024)
        os.replace(tmp_path, ptl_path)

        with zipfile.ZipFile(ptl_path) as zf:
            final_names = set(zf.namelist())
        if "archive/bytecode.pkl" not in final_names:
            raise RuntimeError(f"{os.path.basename(ptl_path)} missing archive/bytecode.pkl after rewrite")

    try:
        from torch.utils.mobile_optimizer import optimize_for_mobile

        opt_enc = optimize_for_mobile(traced_enc)
        opt_dec = optimize_for_mobile(traced_dec)

        save_lite = getattr(torch.jit, "_save_for_lite_interpreter", None)
        if hasattr(opt_enc, "_save_for_lite_interpreter"):
            opt_enc._save_for_lite_interpreter(enc_file)
        elif save_lite is not None:
            save_lite(opt_enc, enc_file)
        else:
            torch.jit.save(opt_enc, enc_file)

        if hasattr(opt_dec, "_save_for_lite_interpreter"):
            opt_dec._save_for_lite_interpreter(dec_file)
        elif save_lite is not None:
            save_lite(opt_dec, dec_file)
        else:
            torch.jit.save(opt_dec, dec_file)
    except Exception:
        torch.jit.save(traced_enc, enc_file)
        torch.jit.save(traced_dec, dec_file)

    _ensure_archive_prefix(enc_file)
    _ensure_archive_prefix(dec_file)

    return enc_file, dec_file


def _sha256_file(path: str, chunk_size: int = 1024 * 1024) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk_size)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def _make_manifest(model_version: str, assets: dict) -> dict:
    now = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    return {
        "modelVersion": model_version,
        "createdAt": now,
        "assets": {
            "encoder.ptl": {"sha256": assets["encoder_sha256"]},
            "decoder.ptl": {"sha256": assets["decoder_sha256"]},
            "ko_vocab.json": {"sha256": assets["ko_vocab_sha256"]},
            "zh_ivocab.json": {"sha256": assets["zh_ivocab_sha256"]},
            "config.json": {"sha256": assets["config_sha256"]},
        },
    }


def create_model_bundle_zip(out_dir_abs: str, model_version: str, zip_path: str | None = None) -> dict:
    enc_path = os.path.join(out_dir_abs, "encoder.ptl")
    dec_path = os.path.join(out_dir_abs, "decoder.ptl")
    ko_path = os.path.join(out_dir_abs, "ko_vocab.json")
    zh_path = os.path.join(out_dir_abs, "zh_ivocab.json")
    cfg_path = os.path.join(out_dir_abs, "config.json")

    assets = {
        "encoder_sha256": _sha256_file(enc_path),
        "decoder_sha256": _sha256_file(dec_path),
        "ko_vocab_sha256": _sha256_file(ko_path),
        "zh_ivocab_sha256": _sha256_file(zh_path),
        "config_sha256": _sha256_file(cfg_path),
    }
    manifest = _make_manifest(model_version, assets)

    if zip_path is None:
        base_dir = os.path.dirname(out_dir_abs)
        zip_path = os.path.join(base_dir, f"model_bundle_{model_version}.zip")

    tmp_zip = zip_path + ".tmp"
    if os.path.exists(tmp_zip):
        os.remove(tmp_zip)

    with zipfile.ZipFile(tmp_zip, "w", compression=zipfile.ZIP_STORED) as zf:
        zf.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        zf.write(enc_path, arcname="encoder.ptl")
        zf.write(dec_path, arcname="decoder.ptl")
        zf.write(ko_path, arcname="ko_vocab.json")
        zf.write(zh_path, arcname="zh_ivocab.json")
        zf.write(cfg_path, arcname="config.json")

    os.replace(tmp_zip, zip_path)
    bundle_sha256 = _sha256_file(zip_path)
    return {
        "zip_path": os.path.abspath(zip_path),
        "zip_sha256": bundle_sha256,
        "model_version": model_version,
        "manifest": manifest,
    }


def run_export(model_dir="Translate Model", model="best_model_v3_attn.pth", ko_vocab="best_ko_vocab_v3_attn.pkl", zh_vocab="best_zh_vocab_v3_attn.pkl", out_dir="mobile_assets"):
    base_dir = os.path.dirname(os.path.abspath(__file__))
    model_dir_abs = _resolve_path(base_dir, model_dir)
    out_dir_abs = _resolve_path(base_dir, out_dir)

    model_path = os.path.join(model_dir_abs, model)
    ko_vocab_path = os.path.join(model_dir_abs, ko_vocab)
    zh_vocab_path = os.path.join(model_dir_abs, zh_vocab)

    with open(ko_vocab_path, "rb") as f:
        ko_vocab_obj = pickle.load(f)
    with open(zh_vocab_path, "rb") as f:
        zh_vocab_obj = pickle.load(f)

    ko_json, zh_json = export_vocabs(ko_vocab_obj, zh_vocab_obj, out_dir_abs)
    cfg_json = export_config(ko_vocab_obj, zh_vocab_obj, out_dir_abs)
    enc_pt, dec_pt = export_torchscript(model_path, ko_vocab_obj, zh_vocab_obj, out_dir_abs)

    return {
        "ko_vocab_json": ko_json,
        "zh_ivocab_json": zh_json,
        "config_json": cfg_json,
        "encoder_pt": enc_pt,
        "decoder_pt": dec_pt,
        "out_dir": out_dir_abs,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model_dir", default="Translate Model")
    ap.add_argument("--model", default="best_model_v3_attn.pth")
    ap.add_argument("--ko_vocab", default="best_ko_vocab_v3_attn.pkl")
    ap.add_argument("--zh_vocab", default="best_zh_vocab_v3_attn.pkl")
    ap.add_argument("--out_dir", default="mobile_assets")
    ap.add_argument("--model_version", default=None)
    ap.add_argument("--make_zip", action="store_true")
    ap.add_argument("--zip_path", default=None)
    args = ap.parse_args()
    result = run_export(
        model_dir=args.model_dir,
        model=args.model,
        ko_vocab=args.ko_vocab,
        zh_vocab=args.zh_vocab,
        out_dir=args.out_dir,
    )

    if args.make_zip:
        model_version = args.model_version
        if not model_version:
            model_version = datetime.now().strftime("%Y.%m.%d")
        bundle = create_model_bundle_zip(result["out_dir"], model_version, args.zip_path)
        result["model_bundle_zip"] = bundle["zip_path"]
        result["model_bundle_sha256"] = bundle["zip_sha256"]
        result["model_version"] = bundle["model_version"]
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

