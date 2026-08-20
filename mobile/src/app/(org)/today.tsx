import { useCallback, useMemo, useState } from 'react';
import { FlatList, Pressable, TextInput, View } from 'react-native';
import { useFocusEffect, router } from 'expo-router';
import { Briefcase, Search, WifiOff, X, ArrowLeft } from 'lucide-react-native';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Text, EmptyState, LoadingView } from '@/components/ui';
import { CaseRow } from '@/components/CaseRow';
import { dailyDispatchApi } from '@/api/dailyDispatchApi';
import { allocationsApi } from '@/api/allocationsApi';
import { resolveDPD } from '@/utils/allocationHeuristics';
import type { AllocationResponse } from '@/types/domain';
import { SafeAreaView } from 'react-native-safe-area-context';
import { todayIso } from '@/utils/date';

export default function TodayVisitsScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [cases, setCases] = useState<AllocationResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      let data: AllocationResponse[] = [];
      try {
        data = await dailyDispatchApi.myList(todayIso());
      } catch (e) {
        const paged = await allocationsApi.getMyCases(user.id, { size: 200 });
        data = paged.content;
      }
      setCases(data);
      setLoadError(false);
    } catch {
      setLoadError(true);
    }
  }, [user]);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load]),
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    const base = q
      ? cases.filter((c) => c.borrowerName?.toLowerCase().includes(q) || c.loanNumber?.toLowerCase().includes(q))
      : cases;
    return [...base].sort((a, b) => (resolveDPD(b) ?? -1) - (resolveDPD(a) ?? -1));
  }, [cases, search]);

  if (loading) return <LoadingView label="Loading today's visits…" />;

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.canvas }} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, marginTop: spacing.s2 }}>
          <Pressable onPress={() => router.back()} hitSlop={12} style={{ marginTop: -2 }}>
            <ArrowLeft size={20} color={colors.ink1} />
          </Pressable>
          <View style={{ marginTop: -8 }}>
            <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Today's visits</Text>
          </View>
        </View>

        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border, marginBottom: spacing.s2
        }}
        >
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search borrower or loan number"
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s3, color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15 }}
          />
          {search.length > 0 ? (
            <Pressable onPress={() => setSearch('')} hitSlop={8}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          ) : null}
        </View>
      </View>

      <FlatList
        data={filtered}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8 }}
        renderItem={({ item }) => <CaseRow item={item} />}
        refreshing={refreshing}
        onRefresh={onRefresh}
        ListEmptyComponent={
          loadError
            ? <EmptyState icon={WifiOff} title="Couldn't load cases" message="Pull down to try again." />
            : (
              <EmptyState
                icon={Briefcase}
                title="No visits found"
                message={search ? 'Try adjusting your search.' : 'No visits are scheduled for today.'}
              />
            )
        }
      />
    </SafeAreaView>
  );
}
