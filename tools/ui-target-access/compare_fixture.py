#!/usr/bin/env python3
"""Compare two actual observation files. Agreement is layout-only, never a touch/Anchor pass."""
import argparse
import json

PACKAGE = 'com.code2hack.eyeprojector.hostprobe'
IDS = [PACKAGE + ':id/ui_target_click', PACKAGE + ':id/ui_target_hold']


def compare(probe, fixture):
    context = {
        'probeSchemaMatches': probe.get('schema') == 'eyeprojector.ui-target-access.v1',
        'fixtureSchemaMatches': fixture.get('schema') == 'eyeprojector.ui-target-fixture.v1',
        'observationCompleted': probe.get('observationCompleted') is True,
        'targetPackageMatches': probe.get('targetPackage') == PACKAGE,
        'runIdMatches': bool(probe.get('runId')) and probe.get('runId') == fixture.get('runId'),
        'displayMatches': isinstance(fixture.get('displayId'), int)
            and fixture['displayId'] == probe.get('requestedDisplayId'),
        'requestedDisplayReported': probe.get('requestedDisplayReported') is True,
    }
    rows = []
    for resource in IDS:
        views = [v for v in fixture.get('controls', []) if v.get('viewIdResourceName') == resource]
        nodes = [(w, n) for w in probe.get('windows', [])
                 for n in w.get('actionableNodes', []) if n.get('viewIdResourceName') == resource]
        row = {'viewIdResourceName': resource, 'fixtureMatches': len(views), 'nodeMatches': len(nodes)}
        if len(views) == len(nodes) == 1:
            view, (window, node) = views[0], nodes[0]
            bounds = view.get('layoutBoundsOnScreen')
            valid_bounds = (isinstance(bounds, list) and len(bounds) == 4
                            and all(isinstance(v, int) for v in bounds)
                            and bounds[2] > bounds[0] and bounds[3] > bounds[1])
            row.update({
                'fixtureBoundsOnScreen': bounds,
                'nodeBoundsInScreen': node.get('boundsInScreen'),
                'boundsEqual': valid_bounds and bounds == node.get('boundsInScreen'),
                'displayMatches': view.get('displayId') == window.get('displayId') == fixture.get('displayId'),
                'windowMatches': node.get('windowId') == window.get('windowId')
                    and isinstance(window.get('windowId'), int),
                'nodeVisibleAndEnabled': node.get('visibleToUser') is True and node.get('enabled') is True,
                'fixtureShownAndLaidOut': all(view.get(k) is True for k in ('shown', 'attached', 'laidOut')),
            })
            row['agreement'] = all(row[k] for k in ('boundsEqual', 'displayMatches', 'windowMatches',
                                                   'nodeVisibleAndEnabled', 'fixtureShownAndLaidOut'))
        else:
            row['agreement'] = False
        rows.append(row)
    agreement = all(context.values()) and all(row['agreement'] for row in rows)
    return {'scope': 'owned-fixture accessibility/View screen-pixel layout comparison only',
            'context': context, 'controls': rows, 'layoutAgreement': agreement,
            'fixtureObservedElapsedRealtimeMs': fixture.get('observedElapsedRealtimeMs'),
            'probeStartedElapsedRealtimeMs': probe.get('startedElapsedRealtimeMs'),
            'probeFinishedElapsedRealtimeMs': probe.get('finishedElapsedRealtimeMs'),
            'encodedFrameAgreementEstablished': False, 'touchDeliveryEstablished': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('probe')
    parser.add_argument('fixture')
    args = parser.parse_args()
    with open(args.probe) as stream:
        probe = json.load(stream)
    with open(args.fixture) as stream:
        fixture = json.load(stream)
    result = compare(probe, fixture)
    print(json.dumps(result, indent=2))
    return 0 if result['layoutAgreement'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
