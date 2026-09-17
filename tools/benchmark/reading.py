"""Evidence checks for Fanqie's paginated reader. Other layouts fail closed."""
import re

class ReadingProgress:
 def __init__(self):self.page=0;self.chapter=0;self.covered=set()
 def accept(self, rows):
  # Only header/title rows, never a chapter number mentioned in the story body.
  chapters=[];numbers=[]
  for row in rows:
   text=row['text'].strip()
   match=re.match(r'^[<＜〈《]?\s*第\s*([一二三四1234])\s*章',text)
   if match and row['y']<350:
    char=match.group(1);chapters.append(int(char) if char.isdigit() else '一二三四'.index(char)+1)
   match=re.fullmatch(r'(\d+)\s*/\s*(\d+)',text)
   if match and row['y']>1050:numbers.append(int(match.group(1)))
  if len(set(numbers))!=1:raise ValueError('未找到唯一阅读页码，不能证明连续阅读')
  page=numbers[0]
  if page!=self.page+1:raise ValueError('阅读页码重复或跳页')
  chapter=max(chapters) if chapters else self.chapter
  if self.page==0 and chapter!=1:raise ValueError('必须从第一章第一页开始')
  if not self.chapter<=chapter<=self.chapter+1:raise ValueError('章节不连续')
  self.page=page;self.chapter=chapter
  if chapter==4:
   if self.covered!={1,2,3}:raise ValueError('前三章证据不全')
   return True
  self.covered.add(chapter)
  return False
