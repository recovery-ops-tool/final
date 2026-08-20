import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, FileText } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, EmptyState, LoadingView, Card, Badge } from '@/components/ui';
import { messageTemplatesApi, type MessageTemplate } from '@/api/messageTemplatesApi';

export default function MessageTemplatesScreen() {
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [templates, setTemplates] = useState<MessageTemplate[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    try {
      const response = await messageTemplatesApi.list({ size: 100 });
      setTemplates(response.content ?? []);
      setLoadError(false);
    } catch (e) {
      setLoadError(true);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load])
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const getStatusTone = (status: string) => {
    switch (status) {
      case 'ACTIVE':
        return 'success';
      case 'RETIRED':
        return 'error';
      case 'PENDING_DLT':
        return 'warning';
      default:
        return 'neutral';
    }
  };

  const filtered = templates.filter((t) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return (
      t.templateKey?.toLowerCase().includes(q) ||
      t.body?.toLowerCase().includes(q)
    );
  });

  if (loading) return <LoadingView label="Loading message templates..." />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Message templates</Text>
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
            placeholder="Search templates..."
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s3, color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15 }}
          />
          {search.length > 0 ? (
            <Pressable onPress={() => setSearch('')} hitSlop={8}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          ) : null}
        </View>

        <FlatList
          data={filtered}
          keyExtractor={(item) => item.id}
          contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s3 }}
          renderItem={({ item }) => (
            <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1, fontFamily: 'monospace' }}>
                  {item.templateKey}
                </Text>
                <Badge tone={getStatusTone(item.status)} label={item.status} />
              </View>

              <Text variant="body" color="primary">{item.body}</Text>

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 }}>
                <Text variant="caption" color="secondary">{item.channel} · {item.language}</Text>
                <Text variant="caption" color="tertiary">v{item.version}</Text>
              </View>
            </Card>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load templates" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={FileText}
                title="No templates found"
                message="Message templates for notifications will list here."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
const styles = StyleSheet.create({});
