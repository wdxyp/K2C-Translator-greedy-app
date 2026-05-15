import argparse
import json
import os
import pickle
import runpy

import torch


def _resolve_path(base_dir: str, path: str) -> str:
    if os.path.isabs(path):
        return path
    return os.path.normpath(os.path.join(base_dir, path))


def _read_lines(path: str):
    out = []
    with open(path, "r", encoding="utf-8") as f:
        for raw in f:
            s = raw.strip()
            if not s:
                continue
            out.append(s)
    return out


def _default_inputs():
    return [
        "안녕하세요",
        "이것은 테스트입니다",
        "이것은(테스트)입니다",
        "이것은（테스트）입니다",
        "100% 완료",
        "ABC_01/TEST",
        "한국어를 번역합니다",
        "이의는 없습니다",
        "이것을 처리해 주세요",
        "업데이트를 완료했습니다",
        "버전(3.0) 테스트",
        "（123） 괄호 유지",
        "(123) parentheses keep",
    ]


def run_generate(script="实时翻译测试_V3.0(greedy).py", model_dir="Translate Model", model="best_model_v3_attn.pth", ko_vocab="best_ko_vocab_v3_attn.pkl", zh_vocab="best_zh_vocab_v3_attn.pkl", user_dict="user_dict.md", inputs="golden_inputs.txt", out="golden_cases.json", limit=100):
    base_dir = os.path.dirname(os.path.abspath(__file__))
    script_abs = _resolve_path(base_dir, script)
    model_dir_abs = _resolve_path(base_dir, model_dir)
    user_dict_abs = _resolve_path(base_dir, user_dict)
    inputs_abs = _resolve_path(base_dir, inputs) if inputs else None
    out_abs = _resolve_path(base_dir, out)

    m = runpy.run_path(script_abs)
    model_path = os.path.join(model_dir_abs, model)
    ko_path = os.path.join(model_dir_abs, ko_vocab)
    zh_path = os.path.join(model_dir_abs, zh_vocab)

    with open(ko_path, "rb") as f:
        ko_vocab_obj = pickle.load(f)
    with open(zh_path, "rb") as f:
        zh_vocab_obj = pickle.load(f)

    inv_zh_vocab = m["_invert_vocab"](zh_vocab_obj)
    device = torch.device("cpu")

    attn = m["Attention"](512)
    enc = m["Encoder"](len(ko_vocab_obj), 256, 512, 1, 0)
    dec = m["Decoder"](len(zh_vocab_obj), 256, 512, 1, 0, attn)
    model_obj = m["Seq2Seq"](enc, dec, device).to(device)
    model_obj.load_state_dict(torch.load(model_path, map_location=device), strict=True)
    model_obj.eval()

    user_dict_obj = m["load_user_dict_data"](user_dict_abs)

    all_inputs = []
    if inputs_abs and os.path.exists(inputs_abs):
        all_inputs.extend(_read_lines(inputs_abs))
    all_inputs.extend(_default_inputs())

    seen = set()
    uniq = []
    for s in all_inputs:
        if s in seen:
            continue
        seen.add(s)
        uniq.append(s)
        if limit and len(uniq) >= limit:
            break

    cases = []
    for s in uniq:
        out_text = m["translate_sentence"](s, model_obj, ko_vocab_obj, zh_vocab_obj, inv_zh_vocab, device, user_dict_obj, show_tokens=False)
        cases.append({"input": s, "output": out_text})

    with open(out_abs, "w", encoding="utf-8") as f:
        json.dump(
            {
                "meta": {
                    "script": script_abs,
                    "model": model_path,
                    "ko_vocab": ko_path,
                    "zh_vocab": zh_path,
                    "user_dict": user_dict_abs,
                    "count": len(cases),
                },
                "cases": cases,
            },
            f,
            ensure_ascii=False,
            indent=2,
        )
    return out_abs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--script", default="实时翻译测试_V3.0(greedy).py")
    ap.add_argument("--model_dir", default="Translate Model")
    ap.add_argument("--model", default="best_model_v3_attn.pth")
    ap.add_argument("--ko_vocab", default="best_ko_vocab_v3_attn.pkl")
    ap.add_argument("--zh_vocab", default="best_zh_vocab_v3_attn.pkl")
    ap.add_argument("--user_dict", default="user_dict.md")
    ap.add_argument("--inputs", default="golden_inputs.txt")
    ap.add_argument("--out", default="golden_cases.json")
    ap.add_argument("--limit", type=int, default=100)
    args = ap.parse_args()
    path = run_generate(
        script=args.script,
        model_dir=args.model_dir,
        model=args.model,
        ko_vocab=args.ko_vocab,
        zh_vocab=args.zh_vocab,
        user_dict=args.user_dict,
        inputs=args.inputs,
        out=args.out,
        limit=args.limit,
    )
    print(path)


if __name__ == "__main__":
    main()

