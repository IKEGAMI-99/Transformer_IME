import random, struct, json
from pathlib import Path
import torch
import torch.nn as nn
import torch.nn.functional as F

random.seed(7)
torch.manual_seed(7)

# Synthetic, deliberately small corpus for an on-device IME proof of concept.
# Tokens are space-separated so the Android side can use a tiny exact-token vocabulary.
base = [
    "今日 は 天気 が いい です",
    "今日 は 仕事 です",
    "明日 は 休み です",
    "明日 は 会議 です",
    "私 は 今日 東京 に 行きます",
    "私 は 明日 大阪 に 行きます",
    "メール を 送ります",
    "見積もり を 送ります",
    "資料 を 確認 します",
    "内容 を 確認 します",
    "よろしく お願い します",
    "ありがとう ございます",
    "おはよう ございます",
    "お疲れさま です",
    "今日 の 予定 を 確認 します",
    "明日 の 予定 を 確認 します",
    "会議 の 資料 を 送ります",
    "仕事 の メール を 確認 します",
    "カメラ の 設定 を 確認 します",
    "AI は 便利 です",
    "AI を 使います",
    "東京 は 暑い です",
    "大阪 は 暑い です",
    "今日は よろしく お願い します",
    "明日 も よろしく お願い します",
    "少し 待って ください",
    "確認 して ください",
    "連絡 して ください",
    "後で 連絡 します",
    "すぐ に 戻ります",
    "問題 ありません",
    "大丈夫 です",
    "了解 しました",
    "承知 しました",
    "これ は テスト です",
    "この アプリ は 便利 です",
    "日本語 を 入力 します",
    "文字 を 入力 します",
    "次 の 候補 を 表示 します",
]

corpus = list(base)
for day in ["今日", "明日"]:
    for thing in ["仕事", "会議", "予定"]:
        corpus += [f"{day} は {thing} です"] * 8
        corpus += [f"{day} の {thing} を 確認 します"] * 8
for thing in ["メール", "見積もり", "資料", "内容"]:
    corpus += [f"{thing} を 送ります"] * 8
    corpus += [f"{thing} を 確認 します"] * 8
for place in ["東京", "大阪"]:
    corpus += [f"私 は {place} に 行きます"] * 8
for x in ["確認", "連絡"]:
    corpus += [f"{x} して ください"] * 8
    corpus += [f"後で {x} します"] * 8
corpus *= 6
random.shuffle(corpus)

special = ["<bos>", "<unk>"]
words = []
for s in corpus:
    words.extend(s.split())
vocab = special + sorted(set(words))
stoi = {w:i for i,w in enumerate(vocab)}
itos = vocab
V = len(vocab)

CTX=12
D=24
H=3
HD=D//H
FF=48

class TinyBlock(nn.Module):
    def __init__(self):
        super().__init__()
        self.ln1 = nn.LayerNorm(D)
        self.q = nn.Linear(D,D)
        self.k = nn.Linear(D,D)
        self.v = nn.Linear(D,D)
        self.o = nn.Linear(D,D)
        self.ln2 = nn.LayerNorm(D)
        self.ff1 = nn.Linear(D,FF)
        self.ff2 = nn.Linear(FF,D)
    def forward(self,x):
        b,t,d=x.shape
        z=self.ln1(x)
        q=self.q(z).view(b,t,H,HD).transpose(1,2)
        k=self.k(z).view(b,t,H,HD).transpose(1,2)
        v=self.v(z).view(b,t,H,HD).transpose(1,2)
        a=(q@k.transpose(-2,-1))/(HD**0.5)
        mask=torch.triu(torch.ones(t,t,device=x.device,dtype=torch.bool),diagonal=1)
        a=a.masked_fill(mask, -1e9)
        a=F.softmax(a,dim=-1)
        y=(a@v).transpose(1,2).contiguous().view(b,t,D)
        x=x+self.o(y)
        z=self.ln2(x)
        x=x+self.ff2(F.gelu(self.ff1(z), approximate='tanh'))
        return x

class TinyLM(nn.Module):
    def __init__(self):
        super().__init__()
        self.tok=nn.Embedding(V,D)
        self.pos=nn.Embedding(CTX,D)
        self.block=TinyBlock()
        self.lnf=nn.LayerNorm(D)
        self.head=nn.Linear(D,V)
    def forward(self,x):
        b,t=x.shape
        h=self.tok(x)+self.pos(torch.arange(t,device=x.device))[None,:,:]
        h=self.block(h)
        return self.head(self.lnf(h))

examples=[]
for s in corpus:
    ids=[stoi["<bos>"]]+[stoi[w] for w in s.split()]
    for i in range(1,len(ids)):
        prefix=ids[max(0,i-CTX):i]
        examples.append((prefix,ids[i]))

model=TinyLM()
opt=torch.optim.AdamW(model.parameters(),lr=3e-3,weight_decay=0.01)
model.train()
for step in range(1800):
    batch=random.sample(examples, min(96,len(examples)))
    maxlen=max(len(p) for p,_ in batch)
    x=torch.full((len(batch),maxlen), stoi["<bos>"], dtype=torch.long)
    lengths=[]; y=[]
    for j,(p,tgt) in enumerate(batch):
        x[j,:len(p)]=torch.tensor(p)
        lengths.append(len(p)-1)
        y.append(tgt)
    logits=model(x)
    idx=torch.arange(len(batch))
    last=logits[idx,torch.tensor(lengths)]
    loss=F.cross_entropy(last,torch.tensor(y))
    opt.zero_grad(); loss.backward(); opt.step()
    if step % 300 == 0:
        print(step, float(loss.detach()))

model.eval()
outdir=Path(__file__).resolve().parents[1]/"app/src/main/assets"
outdir.mkdir(parents=True,exist_ok=True)
(outdir/"vocab.json").write_text(json.dumps(vocab,ensure_ascii=False),encoding="utf-8")

def arr(t):
    return t.detach().cpu().float().contiguous().view(-1).numpy()
def write_arr(f, t):
    a=arr(t)
    f.write(struct.pack('<I', len(a)))
    f.write(a.astype('<f4').tobytes())

with open(outdir/"tiny_transformer.bin","wb") as f:
    f.write(b'TTIM')
    f.write(struct.pack('<IIIII', 1,V,CTX,D,H))
    f.write(struct.pack('<I', FF))
    sd=model.state_dict()
    order=[
        'tok.weight','pos.weight',
        'block.ln1.weight','block.ln1.bias',
        'block.q.weight','block.q.bias','block.k.weight','block.k.bias','block.v.weight','block.v.bias','block.o.weight','block.o.bias',
        'block.ln2.weight','block.ln2.bias',
        'block.ff1.weight','block.ff1.bias','block.ff2.weight','block.ff2.bias',
        'lnf.weight','lnf.bias','head.weight','head.bias'
    ]
    for k in order:
        write_arr(f,sd[k])

print('vocab',V,'model bytes',(outdir/'tiny_transformer.bin').stat().st_size)
