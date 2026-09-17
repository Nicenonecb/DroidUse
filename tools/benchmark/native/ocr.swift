import Foundation
import Vision
import AppKit
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let image = NSImage(contentsOf: url)!
var rect = CGRect(origin: .zero, size: image.size)
let cg = image.cgImage(forProposedRect: &rect, context: nil, hints: nil)!
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.recognitionLanguages = ["zh-Hans", "en-US"]
request.usesLanguageCorrection = false
try VNImageRequestHandler(cgImage: cg).perform([request])
var targets = [[String: Any]]()
let controls = ["分类", "都市脑洞", "都市", "筛选", "书籍", "搜索", "目录", "开始阅读", "立即阅读", "免费阅读", "排行榜", "男生", "男频", "高分", "下一章", "书城", "首页", "完结", "最热", "评分"]
let rows = (request.results ?? []).compactMap { observation -> [String: Any]? in
    guard let candidate = observation.topCandidates(1).first else { return nil }
    func item(_ text: String, _ b: CGRect) -> [String: Any] {
        return ["text": text, "x": Int(b.midX * Double(cg.width)), "y": Int((1-b.midY)*Double(cg.height)), "confidence": candidate.confidence]
    }
    let words = candidate.string.components(separatedBy: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "&|")))
    for word in Set(words + controls) where !word.isEmpty {
        var start = candidate.string.startIndex
        while start < candidate.string.endIndex, let range = candidate.string.range(of: word, range: start..<candidate.string.endIndex) {
            if let box = try? candidate.boundingBox(for: range) { targets.append(item(word, box.boundingBox)) }
            start = range.upperBound
        }
    }
    return item(candidate.string, observation.boundingBox)
}
let data = try JSONSerialization.data(withJSONObject: ["rows": rows, "targets": targets])
print(String(data: data, encoding: .utf8)!)
