import threading,time,unittest
from types import SimpleNamespace
from unittest.mock import patch
from adb_backend import Backend

class BackendTests(unittest.TestCase):
 def backend(self):
  b=Backend.__new__(Backend);b.stopped=threading.Event();b.paused=threading.Event();b.lock=threading.RLock();b.display=9;b.host=SimpleNamespace(poll=lambda:None);b.seen=set();b.start=time.monotonic()
  b.latest={'id':'frame','hostTime':time.monotonic(),'rows':[],'targets':[{'text':'筛选','x':660,'y':220}]}
  b.check_foreground=lambda:None;b.commands=[];b.shell=lambda *a:b.commands.append(a);b.timed=lambda _,fn:fn()
  return b
 def test_main_display_never_receives_input(self):
  b=self.backend();b.display=0
  self.assertEqual('ISOLATION_LOST',b.action('r','frame',{'kind':'tap','x':2,'y':2}));self.assertEqual([],b.commands)
 def test_dead_host_stops_before_input(self):
  b=self.backend();b.host=SimpleNamespace(poll=lambda:1)
  self.assertEqual('ISOLATION_LOST',b.action('r','frame',{'kind':'back'}));self.assertEqual([],b.commands)
 def test_semantic_tap_corrects_only_visible_target(self):
  b=self.backend()
  with patch('adb_backend.time.sleep'):
   self.assertEqual('EXECUTED',b.action('r','frame',{'kind':'tap','x':100,'y':100,'target':'筛选'}))
  self.assertEqual(('input','-d',9,'tap',660,220),b.commands[0])
  self.assertEqual('UNKNOWN_OUTCOME',b.action('r','frame',{'kind':'back'}));self.assertEqual(1,len(b.commands))
 def test_missing_target_does_not_guess(self):
  b=self.backend();self.assertEqual('UNSUPPORTED',b.action('r','frame',{'kind':'tap','x':10,'y':10,'target':'不存在'}));self.assertEqual([],b.commands)
 def test_stale_frame_rejected(self):
  b=self.backend();b.latest['hostTime']-=31
  self.assertEqual('STALE_OBSERVATION',b.action('r','frame',{'kind':'back'}));self.assertEqual([],b.commands)
 def test_pause_blocks_until_resume(self):
  b=self.backend();b.set_paused(True);done=threading.Event()
  t=threading.Thread(target=lambda:(b.await_active(),done.set()));t.start()
  self.assertFalse(done.wait(.05));b.set_paused(False);t.join(1);self.assertTrue(done.is_set())
 def test_cancel_interrupts_paused_reader(self):
  b=self.backend();b.set_paused(True);b.stopped.set()
  with self.assertRaises(RuntimeError):b.await_active()

if __name__=='__main__':unittest.main()
