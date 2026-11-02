"""Synthetic comparison inputs only; never Android evidence."""
import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('compare_fixture', Path(__file__).resolve().parents[1] / 'compare_fixture.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ComparisonTests(unittest.TestCase):
    def setUp(self):
        self.fixture = {'schema': 'eyeprojector.ui-target-fixture.v1', 'runId': 'host-double',
                        'displayId': 7, 'controls': []}
        self.probe = {'schema': 'eyeprojector.ui-target-access.v1', 'runId': 'host-double',
                      'targetPackage': MODULE.PACKAGE, 'observationCompleted': True,
                      'requestedDisplayId': 7, 'requestedDisplayReported': True,
                      'windows': [{'displayId': 7, 'windowId': 12, 'actionableNodes': []}]}
        for i, resource in enumerate(MODULE.IDS):
            bounds = [20, 20 + i * 100, 240, 100 + i * 100]
            self.fixture['controls'].append({'viewIdResourceName': resource, 'displayId': 7,
                'layoutBoundsOnScreen': bounds, 'shown': True, 'attached': True, 'laidOut': True})
            self.probe['windows'][0]['actionableNodes'].append({'viewIdResourceName': resource,
                'windowId': 12, 'boundsInScreen': bounds[:], 'visibleToUser': True, 'enabled': True})

    def test_equal_layout_has_narrow_scope(self):
        result = MODULE.compare(self.probe, self.fixture)
        self.assertTrue(result['layoutAgreement'])
        self.assertFalse(result['touchDeliveryEstablished'])
        self.assertFalse(result['encodedFrameAgreementEstablished'])

    def test_wrong_context_or_partial_observation(self):
        for key, value in [('requestedDisplayId', 0), ('requestedDisplayReported', False),
                           ('runId', 'old-run'), ('targetPackage', 'other'), ('observationCompleted', False)]:
            with self.subTest(key=key):
                probe = copy.deepcopy(self.probe)
                probe[key] = value
                self.assertFalse(MODULE.compare(probe, self.fixture)['layoutAgreement'])

    def test_missing_duplicate_moved_and_wrong_window_nodes(self):
        for mutation in ['missing', 'duplicate', 'moved', 'wrong-window', 'wrong-display']:
            with self.subTest(mutation=mutation):
                probe = copy.deepcopy(self.probe)
                nodes = probe['windows'][0]['actionableNodes']
                if mutation == 'missing':
                    nodes.pop()
                elif mutation == 'duplicate':
                    nodes.append(copy.deepcopy(nodes[0]))
                elif mutation == 'moved':
                    nodes[0]['boundsInScreen'][0] += 1
                elif mutation == 'wrong-window':
                    nodes[0]['windowId'] = 99
                else:
                    probe['windows'][0]['displayId'] = 0
                self.assertFalse(MODULE.compare(probe, self.fixture)['layoutAgreement'])

    def test_absent_or_empty_bounds_are_not_agreement(self):
        for bounds in [None, [], [0, 0, 0, 0]]:
            self.fixture['controls'][0]['layoutBoundsOnScreen'] = bounds
            self.probe['windows'][0]['actionableNodes'][0]['boundsInScreen'] = bounds
            self.assertFalse(MODULE.compare(self.probe, self.fixture)['layoutAgreement'])


if __name__ == '__main__':
    unittest.main()
