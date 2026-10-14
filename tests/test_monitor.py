import unittest
from datetime import datetime

from app import campus_matches, course_fingerprint, fits_free_hours, normalize_course, parse_course_rows, parse_datetime


class MonitorCoreTests(unittest.TestCase):
    def test_parse_datetime_and_page_row_keeps_activity_and_selection_separate(self):
        rows = [["可选", "人工智能导论", "人工智能 / 博雅", "地点：学院路 主讲：张老师", "开始：2026-10-20 09:00 结束：2026-10-20 11:00", "校区：学院路校区", "选课开始：2026-10-14 08:00 选课结束：2026-10-19 23:00", "无作业", "12/30"]]
        course = parse_course_rows(rows)[0]
        self.assertEqual(course["start"], "2026-10-20T09:00")
        self.assertEqual(course["select_start"], "2026-10-14T08:00")
        self.assertNotEqual(course["start"], course["select_start"])
        self.assertEqual(course["current_count"], 12)
        self.assertEqual(course["max_count"], 30)

    def test_free_hours_requires_every_course_hour(self):
        course = normalize_course({"name": "课", "start": "2026-10-19 09:00", "end": "2026-10-19 11:00", "campus": "沙河"})
        self.assertTrue(fits_free_hours(course, {"0": [9, 10]}))
        self.assertFalse(fits_free_hours(course, {"0": [9]}))

    def test_campus_aliases(self):
        course = normalize_course({"name": "课", "start": "2026-10-19 09:00", "campus": ["学院路校区"]})
        self.assertTrue(campus_matches(course, "学院路", "offline"))
        self.assertFalse(campus_matches(course, "沙河", "offline"))

    def test_online_is_a_delivery_mode(self):
        course = normalize_course({"name": "线上课", "start": "2026-10-19 09:00", "campus": "线上"})
        self.assertTrue(campus_matches(course, "学院路", "online"))
        self.assertFalse(campus_matches(course, "学院路", "offline"))

    def test_location_text_overrides_unmarked_or_all_campus(self):
        course = normalize_course({"name": "地点课", "start": "2026-10-19 09:00", "campus": "全部校区", "position": "地点：沙河校区体育馆"})
        self.assertFalse(campus_matches(course, "学院路", "offline"))
        self.assertTrue(campus_matches(course, "沙河", "offline"))

    def test_course_fingerprint_changes_when_capacity_changes(self):
        first = normalize_course({"name": "课", "start": "2026-10-19 09:00", "current_count": 1, "max_count": 20})
        second = normalize_course({"name": "课", "start": "2026-10-19 09:00", "current_count": 2, "max_count": 20})
        self.assertNotEqual(course_fingerprint(first), course_fingerprint(second))

    def test_invalid_datetime_is_safe(self):
        self.assertIsNone(parse_datetime("not-a-date"))


if __name__ == "__main__":
    unittest.main()
