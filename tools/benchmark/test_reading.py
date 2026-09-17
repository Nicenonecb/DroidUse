import unittest
from reading import ReadingProgress

def rows(page,chapter=None,body='小说正文'):
 result=[{'text':f'{page}/100','y':1180},{'text':body,'y':550}]
 if chapter is not None:result.append({'text':f'＜第{chapter}章 标题','y':145})
 return result

class ReadingTests(unittest.TestCase):
 def test_requires_first_page(self):
  with self.assertRaises(ValueError):ReadingProgress().accept(rows(2,1))
 def test_requires_first_chapter(self):
  with self.assertRaises(ValueError):ReadingProgress().accept(rows(1,2))
 def test_skipped_page_rejected(self):
  r=ReadingProgress();r.accept(rows(1,1))
  with self.assertRaises(ValueError):r.accept(rows(3,1))
 def test_repeated_page_rejected(self):
  r=ReadingProgress();r.accept(rows(1,1))
  with self.assertRaises(ValueError):r.accept(rows(1,1))
 def test_body_chapter_reference_is_not_boundary(self):
  r=ReadingProgress();self.assertFalse(r.accept(rows(1,1,'第4章 故事里提到的章节')));self.assertEqual(1,r.chapter)
 def test_missing_page_rejected(self):
  with self.assertRaises(ValueError):ReadingProgress().accept([{'text':'第1章 起点','y':120}])
 def test_skipped_chapter_rejected(self):
  r=ReadingProgress();r.accept(rows(1,1))
  with self.assertRaises(ValueError):r.accept(rows(2,3))
 def test_only_fourth_boundary_proves_complete(self):
  r=ReadingProgress()
  self.assertFalse(r.accept(rows(1,1)));self.assertFalse(r.accept(rows(2)))
  self.assertFalse(r.accept(rows(3,2)));self.assertFalse(r.accept(rows(4,3)))
  self.assertTrue(r.accept(rows(5,4)));self.assertEqual({1,2,3},r.covered)

if __name__=='__main__':unittest.main()
