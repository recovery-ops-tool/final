import { useCallback, useEffect, useState } from 'react';
import { View } from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as Location from 'expo-location';
import { CalendarCheck, IndianRupee, Handshake, MapPinCheck, CloudOff, RefreshCw, Radio } from 'lucide-react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, Button, Card, StatCard, EmptyState, LoadingView, Divider } from '@/components/ui';
import { CaseRow } from '@/components/CaseRow';
import { dailyDispatchApi } from '@/api/dailyDispatchApi';
import { allocationsApi } from '@/api/allocationsApi';
import { attendanceApi } from '@/api/attendanceApi';
import { dashboardApi } from '@/api/dashboardApi';
import { todayIso, formatTime, formatDurationSince } from '@/utils/date';
import { formatCurrency } from '@/utils/allocationHeuristics';
import { extractApiError } from '@/utils/extractApiError';
import { useOfflineSync } from '@/hooks/useOfflineSync';
import { useShiftTracking } from '@/hooks/useShiftTracking';
import { trySync } from '@/utils/offlineQueue';
import type { AllocationResponse, FieldAgentDashboardResponse } from '@/types/domain';

export default function HomeScreen() {
  const { user } = useAuth();
  const { colors, spacing } = useTheme();
  const insets = useSafeAreaInsets();
  const { pending, syncing } = useOfflineSync();
  const { shift, starting, ending, error: shiftError, startShift, endShift } = useShiftTracking();
  const [, forceTick] = useState(0);

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [todayCases, setTodayCases] = useState<AllocationResponse[]>([]);
  const [checkedInAt, setCheckedInAt] = useState<string | null>(null);
  const [checkingIn, setCheckingIn] = useState(false);
  const [checkInError, setCheckInError] = useState<string | null>(null);
  const [dashboard, setDashboard] = useState<FieldAgentDashboardResponse | null>(null);

  const load = useCallback(async () => {
    if (!user) return;
    const today = todayIso();

    const casesPromise = dailyDispatchApi.myList(today).catch(async () => {
      const paged = await allocationsApi.getMyCases(user.id, { size: 50 }).catch(() => null);
      return paged?.content ?? [];
    });

    const attendancePromise = attendanceApi.me(today, today).catch(() => []);
    const dashboardPromise = dashboardApi.fieldAgent(user.id).catch(() => null);

    const [cases, attendance, dash] = await Promise.all([casesPromise, attendancePromise, dashboardPromise]);

    setTodayCases(cases);
    setCheckedInAt(attendance[0]?.checkedInAt ?? null);
    setDashboard(dash);
  }, [user]);

  const completedByAllocationId = new Set(
    (dashboard?.todayAssignments ?? []).filter((a) => a.status === 'COMPLETED').map((a) => a.allocationId),
  );

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load]),
  );

  useEffect(() => {
    if (!shift) return;
    const t = setInterval(() => forceTick((n) => n + 1), 60_000);
    return () => clearInterval(t);
  }, [shift]);

  const onRefresh = async () => {
    setRefreshing(true);
    await load().catch(() => {});
    setRefreshing(false);
  };

  const onCheckIn = async () => {
    setCheckInError(null);
    setCheckingIn(true);
    try {
      const { status } = await Location.requestForegroundPermissionsAsync();
      let coords: { lat?: number; lng?: number; accuracy?: number } = {};
      if (status === 'granted') {
        const pos = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
        coords = { lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy ?? undefined };
      }
      const result = await attendanceApi.checkIn(coords);
      setCheckedInAt(result.checkedInAt);
    } catch (e) {
      setCheckInError(extractApiError(e, 'Could not check in. Try again.'));
    } finally {
      setCheckingIn(false);
    }
  };

  if (loading) return <LoadingView label="Loading your day…" />;

  return (
    <Screen onRefresh={onRefresh} refreshing={refreshing} padded={false} edges={['left', 'right']}>
      <StatusBar style="light" backgroundColor="#0AA550" />
      {/* Top Banner section with green background extending under status bar */}
      <View style={{ backgroundColor: '#0AA550', paddingHorizontal: spacing.s4, paddingTop: insets.top + spacing.s4, paddingBottom: spacing.s5, borderBottomLeftRadius: 24, borderBottomRightRadius: 24 }}>
        <View style={{ marginBottom: spacing.s4 }}>
          <Text variant="caption" style={{ color: '#E8F5E9' }}>Welcome back</Text>
          <Text variant="title" style={{ color: '#FFFFFF', fontWeight: 'bold' }}>{user?.firstName ?? 'Field Officer'}</Text>
        </View>

        {pending > 0 ? (
          <Card style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, marginBottom: spacing.s4, backgroundColor: '#FFFFFF', borderWidth: 0, shadowOpacity: 0.05 }}>
            <CloudOff size={18} color={colors.warnBorder} />
            <Text variant="caption" color="secondary" style={{ flex: 1 }}>
              {pending} {pending === 1 ? 'item' : 'items'} waiting to sync
            </Text>
            <Button
              label="Sync now"
              variant="ghost"
              fullWidth={false}
              size="md"
              loading={syncing}
              onPress={() => trySync()}
              icon={<RefreshCw size={14} color={colors.accent} />}
            />
          </Card>
        ) : null}

        <Card style={{ gap: spacing.s4, shadowOpacity: 0, elevation: 0 }}>
          {/* Field Shift Section */}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
            <View style={{ flex: 1, gap: spacing.s1 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
                <Radio size={16} color={shift ? colors.success : colors.ink3} />
                <Text variant="bodyMedium">Field shift</Text>
              </View>
              {shift ? (
                <Text variant="caption" color="secondary">
                  Live — sharing location for {formatDurationSince(shift.startedAt)}
                </Text>
              ) : null}
              {shiftError ? <Text variant="caption" color="error">{shiftError}</Text> : null}
            </View>
            {shift ? (
              <Button label="End shift" variant="outline" onPress={endShift} loading={ending} fullWidth={false} size="md" />
            ) : (
              <Button label="Start shift" onPress={startShift} loading={starting} fullWidth={false} size="md" />
            )}
          </View>

          {/* Attendance Check-in Row underneath */}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
            <View style={{ flex: 1, gap: spacing.s1 }}>
              <Text variant="caption" color="secondary">
                {checkedInAt ? `Checked in at ${formatTime(checkedInAt)}` : "You haven't checked in today"}
              </Text>
              {checkInError ? <Text variant="caption" color="error">{checkInError}</Text> : null}
            </View>
            {!checkedInAt ? (
              <Button label="Check in" onPress={onCheckIn} loading={checkingIn} fullWidth={false} icon={<MapPinCheck size={16} color="#fff" />} size="md" />
            ) : null}
          </View>
        </Card>
      </View>

      {/* Main body content below the green header */}
      <View style={{ padding: spacing.s4, gap: spacing.s5 }}>
        <View style={{ flexDirection: 'row', gap: spacing.s3 }}>
          <StatCard icon={IndianRupee} label="Collected today" value={dashboard ? formatCurrency(dashboard.collectedAmountToday) : '—'} tone="success" />
          <StatCard icon={Handshake} label="PTPs due today" value={dashboard ? String(dashboard.ptpsDueToday) : '—'} tone="warning" />
        </View>

        <View style={{ gap: spacing.s3 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
            <Text variant="headline">Today's visits</Text>
            <Text variant="caption" color="accent" onPress={() => router.push('/(tabs)/cases')}>View all cases</Text>
          </View>

          {dashboard && dashboard.todayTotalCases > 0 ? (
            <Text variant="caption" color="secondary">
              {dashboard.todayCompletedCases} of {dashboard.todayTotalCases} done ({Math.round(dashboard.todayCompletionRate)}%)
            </Text>
          ) : null}

          {todayCases.length === 0 ? (
            <EmptyState icon={CalendarCheck} title="No visits scheduled today" message="Cases assigned to you will show up here once dispatched." />
          ) : (
            todayCases.map((item) => (
              <CaseRow key={item.id} item={item} completed={completedByAllocationId.has(item.id)} />
            ))
          )}
        </View>
      </View>
    </Screen>
  );
}
