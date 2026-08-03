import { useCallback, useState } from 'react';
import { View, ScrollView } from 'react-native';
import { useLocalSearchParams, useRouter, useFocusEffect } from 'expo-router';
import { Calendar, Clock, ChevronLeft, User, Phone, CheckCircle, AlertCircle, FileText } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, Card, Badge, Divider, Button } from '@/components/ui';
import { visitLogApi } from '@/api/visitLogApi';
import type { VisitLogResponse } from '@/types/domain';

export default function VisitDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const { colors, spacing } = useTheme();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [log, setLog] = useState<VisitLogResponse | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    try {
      const data = await visitLogApi.getById(id);
      setLog(data);
      setError(null);
    } catch (e) {
      console.log('Failed to fetch visit log details', e);
      setError('Could not load visit details. Please try again.');
    }
  }, [id]);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load])
  );

  const getDispTone = (disp?: string) => {
    switch (disp) {
      case 'PAID':
        return 'success';
      case 'PTP':
        return 'warning';
      case 'CONTACTED':
        return 'info';
      case 'NO_CONTACT':
        return 'error';
      default:
        return 'neutral';
    }
  };

  const getApprovalTone = (status?: string) => {
    switch (status) {
      case 'APPROVED':
        return 'success';
      case 'REJECTED':
        return 'error';
      default:
        return 'warning';
    }
  };

  if (loading) return <LoadingView label="Loading visit details…" />;

  if (error || !log) {
    return (
      <Screen>
        <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', gap: spacing.s4, padding: spacing.s4 }}>
          <AlertCircle size={48} color={colors.error} />
          <Text variant="body" style={{ textAlign: 'center' }}>{error || 'Visit report not found'}</Text>
          <Button label="Go Back" onPress={() => router.back()} />
        </View>
      </Screen>
    );
  }

  return (
    <Screen padded={false}>
      {/* Header bar */}
      <View style={{ 
        flexDirection: 'row', 
        alignItems: 'center', 
        paddingHorizontal: spacing.s4, 
        paddingTop: spacing.s3, 
        paddingBottom: spacing.s3,
        borderBottomWidth: 1,
        borderColor: colors.border,
        backgroundColor: colors.surface
      }}>
        <Pressable onPress={() => router.back()} style={{ padding: 4, marginRight: spacing.s2 }}>
          <ChevronLeft size={24} color={colors.ink1} />
        </Pressable>
        <Text variant="headline" style={{ fontWeight: '700' }}>Visit Report</Text>
      </View>

      <ScrollView contentContainerStyle={{ padding: spacing.s4, gap: spacing.s4 }}>
        
        {/* Borrower details card */}
        <Card style={{ gap: spacing.s3 }}>
          <Text variant="caption" color="secondary" style={{ textTransform: 'uppercase', fontWeight: '700' }}>Borrower Info</Text>
          <View style={{ gap: 2 }}>
            <Text variant="title" style={{ color: colors.ink1 }}>{log.borrowerName || 'Unknown Borrower'}</Text>
            <Text variant="body" color="secondary">Loan Number: {log.loanNumber || '—'}</Text>
          </View>
          <Divider />
          <Button 
            label="View Case Details" 
            variant="outline" 
            size="md" 
            onPress={() => router.push({ pathname: '/case/[id]', params: { id: log.allocationId } })} 
          />
        </Card>

        {/* Visit details card */}
        <Card style={{ gap: spacing.s3 }}>
          <Text variant="caption" color="secondary" style={{ textTransform: 'uppercase', fontWeight: '700' }}>Visit Information</Text>
          
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <Text variant="bodyMedium" style={{ fontWeight: '600' }}>Outcome</Text>
            {log.disp ? (
              <Badge tone={getDispTone(log.disp)} label={log.disp} />
            ) : null}
          </View>

          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <Text variant="bodyMedium" style={{ fontWeight: '600' }}>Approval Status</Text>
            <Badge tone={getApprovalTone(log.approvalStatus)} label={log.approvalStatus} />
          </View>

          <Divider style={{ marginVertical: 4 }} />

          {/* Date & Time */}
          <View style={{ flexDirection: 'row', gap: spacing.s4 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2, flex: 1 }}>
              <Calendar size={18} color={colors.ink3} />
              <View>
                <Text variant="caption" color="secondary">Visit Date</Text>
                <Text variant="body">{log.visitDate}</Text>
              </View>
            </View>
            {log.visitTime ? (
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2, flex: 1 }}>
                <Clock size={18} color={colors.ink3} />
                <View>
                  <Text variant="caption" color="secondary">Visit Time</Text>
                  <Text variant="body">{log.visitTime}</Text>
                </View>
              </View>
            ) : null}
          </View>

          <Divider style={{ marginVertical: 4 }} />

          {/* Contactability */}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
            <User size={18} color={colors.ink3} />
            <View>
              <Text variant="caption" color="secondary">Contactability</Text>
              <Text variant="body">{log.contactability || '—'}</Text>
            </View>
          </View>

          {/* Contact Person */}
          {log.contactPerson ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
              <User size={18} color={colors.ink3} />
              <View>
                <Text variant="caption" color="secondary">Contacted Person</Text>
                <Text variant="body">{log.contactPerson}</Text>
              </View>
            </View>
          ) : null}

          {/* Contact Number */}
          {log.contactNumber ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
              <Phone size={18} color={colors.ink3} />
              <View>
                <Text variant="caption" color="secondary">Contact Phone</Text>
                <Text variant="body">{log.contactNumber}</Text>
              </View>
            </View>
          ) : null}
        </Card>

        {/* Narrative / Notes */}
        {log.visitOutcome ? (
          <Card style={{ gap: spacing.s2 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
              <FileText size={18} color={colors.ink3} />
              <Text variant="caption" color="secondary" style={{ textTransform: 'uppercase', fontWeight: '700' }}>Visit Notes</Text>
            </View>
            <Text variant="body" style={{ fontStyle: 'italic', lineHeight: 20 }}>
              "{log.visitOutcome}"
            </Text>
          </Card>
        ) : null}

        {/* Approval feedback */}
        {log.approvalRemarks ? (
          <Card style={{ gap: spacing.s2, backgroundColor: log.approvalStatus === 'APPROVED' ? '#E6F4EA' : '#FCE8E6', borderWidth: 0 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
              <CheckCircle size={18} color={log.approvalStatus === 'APPROVED' ? '#137333' : '#D93025'} />
              <Text variant="caption" style={{ fontWeight: '700', color: log.approvalStatus === 'APPROVED' ? '#137333' : '#D93025' }}>
                REVIEWER REMARKS
              </Text>
            </View>
            <Text variant="body" style={{ color: log.approvalStatus === 'APPROVED' ? '#137333' : '#D93025' }}>
              {log.approvalRemarks}
            </Text>
          </Card>
        ) : null}

      </ScrollView>
    </Screen>
  );
}
import { Pressable } from 'react-native';
import { LoadingView } from '@/components/ui';
