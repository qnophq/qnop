/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import { useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Divider from '@mui/material/Divider';
import FormControlLabel from '@mui/material/FormControlLabel';
import Autocomplete from '@mui/material/Autocomplete';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import InputAdornment from '@mui/material/InputAdornment';
import TextField from '@mui/material/TextField';
import Step from '@mui/material/Step';
import StepLabel from '@mui/material/StepLabel';
import Stepper from '@mui/material/Stepper';
import Typography from '@mui/material/Typography';
import { alpha } from '@mui/material/styles';
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Download,
  Image as ImageIcon,
  MessagesSquare,
} from 'lucide-react';
import { ToneBadge } from '../../admin/ToneBadge';
import { useUiStore } from '../../../stores/uiStore';
import { listTimeZones, timeZoneLabel } from '../../../utils/timezone';
import {
  EXPORT_DATE_FORMATS,
  EXPORT_FIELDS,
  EXPORT_FORMATS,
  defaultFileName,
  FIELD_GROUPS,
  effectiveFields,
  loadSettings,
  saveSettings,
  type ExportScope,
  type ExportSettings,
} from './exportModel';

const STEPS = ['Format & scope', 'Fields'];

const SCOPES: { id: ExportScope; label: string; hint: string }[] = [
  { id: 'all', label: 'Everything', hint: 'Every annotation in this review' },
  { id: 'open', label: 'Open only', hint: 'What still needs work' },
  { id: 'resolved', label: 'Resolved only', hint: 'What has been settled' },
];

export interface ExportCounts {
  all: number;
  open: number;
  resolved: number;
}

/**
 * Configures an annotation export before it runs (issue #547).
 *
 * <p>A wizard rather than a menu because the choices interact: the format
 * decides what the fields even mean, and the scope decides how many rows there
 * will be. Splitting them across two steps keeps each screen answerable at a
 * glance instead of presenting eleven checkboxes and six formats at once.
 *
 * <p>Three things earn their place beyond what was asked for. The <em>scope</em>
 * turns the export into a report ("just the open findings") instead of always a
 * full dump. The <em>row count</em> is shown before the download, because an
 * export is a one-shot action with no undo and no preview once it lands in the
 * downloads folder. And the settings are <em>remembered</em>, since exporting a
 * review is something people do repeatedly and reconfiguring every time is a
 * tax on the frequent case.
 */
export function ExportWizard({
  open,
  onClose,
  onExport,
  counts,
  documentTitle,
  exporting = false,
}: {
  open: boolean;
  onClose: () => void;
  onExport: (settings: ExportSettings, fileName: string) => void;
  /** The review's title, which the prefilled filename is derived from. */
  documentTitle?: string | null;
  /** Row counts per scope, so the wizard can say what the download will contain. */
  counts?: ExportCounts;
  exporting?: boolean;
}) {
  // The caller mounts this only while it is open, so the initialisers run on
  // every open: first step, last configuration. A reset effect would do the
  // same thing a render too late, and cascade.
  const [step, setStep] = useState(0);
  // The reader's own zone (ADR-0041), so the wizard opens on the times they
  // actually work in rather than on UTC.
  const displayTimeZone = useUiStore((state) => state.displayTimeZone);
  const [settings, setSettings] = useState<ExportSettings>(() => loadSettings(displayTimeZone));
  const zones = useMemo(() => listTimeZones(displayTimeZone), [displayTimeZone]);
  // Only what the user typed is state; the default is derived on every render.
  // Capturing it once would freeze whatever the title was at mount — which, on a
  // cold cache, is nothing at all, and the field would sit on "annotations" even
  // after the review's name arrived.
  //
  // Not persisted with the rest either: a filename belongs to this review, and
  // carrying last week's name onto a different document is a trap, not a
  // convenience.
  const [typedFileName, setTypedFileName] = useState<string | null>(null);
  const fileName = typedFileName ?? defaultFileName(documentTitle);

  const selected = useMemo(() => effectiveFields(settings.fields), [settings.fields]);
  const rows = counts ? counts[settings.scope] : null;
  const format = EXPORT_FORMATS.find((entry) => entry.id === settings.format);

  const toggleField = (id: string) =>
    setSettings((current) => ({
      ...current,
      fields: current.fields.includes(id)
        ? current.fields.filter((field) => field !== id)
        : [...current.fields, id],
    }));

  const setAllFields = (on: boolean) =>
    setSettings((current) => ({
      ...current,
      fields: on ? EXPORT_FIELDS.map((field) => field.id) : [],
    }));

  const start = () => {
    const chosen = { ...settings, fields: selected };
    saveSettings(chosen);
    onExport(chosen, fileName.trim());
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ pb: 1 }}>
        <Typography
          sx={{
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.14em',
            textTransform: 'uppercase',
            color: 'primary.main',
          }}
        >
          Export
        </Typography>
        <Typography component="span" sx={{ fontSize: 20, fontWeight: 800 }}>
          {step === 0 ? 'What do you need?' : 'Which details?'}
        </Typography>
      </DialogTitle>

      <Box sx={{ px: 3, pb: 1 }}>
        <Stepper activeStep={step} sx={{ '& .MuiStepLabel-label': { fontSize: 13 } }}>
          {STEPS.map((label) => (
            <Step key={label}>
              <StepLabel>{label}</StepLabel>
            </Step>
          ))}
        </Stepper>
      </Box>

      <DialogContent dividers>
        {step === 0 ? (
          <Stack spacing={3}>
            <Box>
              <SectionLabel>Format</SectionLabel>
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
                  gap: 1,
                  mt: 1,
                }}
              >
                {EXPORT_FORMATS.map((entry) => {
                  const active = entry.id === settings.format;
                  return (
                    <Box
                      key={entry.id}
                      component="button"
                      type="button"
                      disabled={entry.planned}
                      aria-pressed={active}
                      onClick={() => setSettings((c) => ({ ...c, format: entry.id }))}
                      sx={{
                        textAlign: 'left',
                        p: 1.5,
                        borderRadius: 2,
                        border: '1px solid',
                        borderColor: active ? 'primary.main' : 'divider',
                        bgcolor: (t) =>
                          active ? alpha(t.palette.primary.main, 0.06) : 'transparent',
                        cursor: entry.planned ? 'not-allowed' : 'pointer',
                        opacity: entry.planned ? 0.5 : 1,
                        transition: 'border-color 150ms, background-color 150ms',
                        '&:hover:not(:disabled)': { borderColor: 'primary.main' },
                      }}
                    >
                      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mb: 0.25 }}>
                        <Typography sx={{ fontSize: 14, fontWeight: 700 }}>
                          {entry.label}
                        </Typography>
                        <Typography sx={{ fontSize: 11.5, color: 'text.disabled' }}>
                          {entry.extension}
                        </Typography>
                        {entry.planned && <ToneBadge tone="neutral" label="Planned" />}
                      </Stack>
                      <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                        {entry.hint}
                      </Typography>
                    </Box>
                  );
                })}
              </Box>
            </Box>

            <Box>
              <SectionLabel>Which annotations</SectionLabel>
              <Stack spacing={0.75} sx={{ mt: 1 }}>
                {SCOPES.map((scope) => {
                  const active = scope.id === settings.scope;
                  return (
                    <Box
                      key={scope.id}
                      component="button"
                      type="button"
                      aria-pressed={active}
                      onClick={() => setSettings((c) => ({ ...c, scope: scope.id }))}
                      sx={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 1.5,
                        width: '100%',
                        textAlign: 'left',
                        px: 1.5,
                        py: 1,
                        borderRadius: 2,
                        border: '1px solid',
                        borderColor: active ? 'primary.main' : 'divider',
                        bgcolor: (t) =>
                          active ? alpha(t.palette.primary.main, 0.06) : 'transparent',
                        cursor: 'pointer',
                        '&:hover': { borderColor: 'primary.main' },
                      }}
                    >
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        <Typography sx={{ fontSize: 14, fontWeight: active ? 700 : 500 }}>
                          {scope.label}
                        </Typography>
                        <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                          {scope.hint}
                        </Typography>
                      </Box>
                      {counts && (
                        <Typography sx={{ fontSize: 13, fontWeight: 700, color: 'text.secondary' }}>
                          {counts[scope.id]}
                        </Typography>
                      )}
                    </Box>
                  );
                })}
              </Stack>
            </Box>
          </Stack>
        ) : (
          <Stack spacing={2}>
            <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
              <SectionLabel>{capitalise(format?.fieldNoun ?? 'fields')}</SectionLabel>
              <Stack direction="row" spacing={0.5}>
                <Button size="small" onClick={() => setAllFields(true)}>
                  All
                </Button>
                <Button size="small" onClick={() => setAllFields(false)}>
                  None
                </Button>
              </Stack>
            </Stack>

            {FIELD_GROUPS.map((group) => (
              <Box key={group}>
                <Typography
                  sx={{ fontSize: 11.5, fontWeight: 700, color: 'text.disabled', mb: 0.25 }}
                >
                  {group}
                </Typography>
                <Box
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
                    columnGap: 2,
                  }}
                >
                  {EXPORT_FIELDS.filter((field) => field.group === group).map((field) => (
                    <FormControlLabel
                      key={field.id}
                      sx={{ m: 0 }}
                      control={
                        <Checkbox
                          size="small"
                          checked={selected.includes(field.id)}
                          disabled={field.required}
                          onChange={() => toggleField(field.id)}
                        />
                      }
                      label={
                        <Typography sx={{ fontSize: 13.5 }}>
                          {field.label}
                          {field.required && (
                            <Typography
                              component="span"
                              sx={{ fontSize: 11.5, color: 'text.disabled', ml: 0.5 }}
                            >
                              (always)
                            </Typography>
                          )}
                        </Typography>
                      }
                    />
                  ))}
                </Box>
              </Box>
            ))}

            <Divider />

            <SectionLabel>Presentation</SectionLabel>

            <TextField
              size="small"
              label="File name"
              value={fileName}
              onChange={(event) => setTypedFileName(event.target.value)}
              placeholder={defaultFileName(documentTitle)}
              slotProps={{
                input: {
                  // The extension follows the format and is not the user's to
                  // set: typing .pdf on a Word export would name a file that
                  // lies about its own bytes.
                  endAdornment: (
                    <InputAdornment position="end">
                      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                        {format?.extension}
                      </Typography>
                    </InputAdornment>
                  ),
                },
              }}
            />

            {/* Deliberately not a twelfth checkbox: a thread has no fixed length,
                so it cannot be a column. It becomes its own sheet, and saying so
                here is cheaper than a support question about the second tab. */}
            <ToggleCard
              on={settings.includeComments}
              onChange={(on) => setSettings((c) => ({ ...c, includeComments: on }))}
              icon={<MessagesSquare size={14} />}
              title="Comment threads"
              hint={format?.commentsHint ?? ''}
            />

            {format?.supportsLogo && (
              <ToggleCard
                on={settings.includeLogo}
                onChange={(on) => setSettings((c) => ({ ...c, includeLogo: on }))}
                icon={<ImageIcon size={14} />}
                title="Branding logo"
                hint="Places the operator's logo on the document. Nothing is placed if no branding has been uploaded."
              />
            )}

            <Box>
              <Typography sx={{ fontSize: 11.5, fontWeight: 700, color: 'text.disabled', mb: 0.5 }}>
                Date and time
              </Typography>
              <Autocomplete
                size="small"
                disableClearable
                options={zones}
                value={settings.timezone}
                onChange={(_, zone) => setSettings((c) => ({ ...c, timezone: zone }))}
                getOptionLabel={(zone) => timeZoneLabel(zone)}
                renderInput={(params) => <TextField {...params} label="Timezone" />}
                sx={{ mb: 1 }}
              />
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
                  gap: 0.75,
                }}
              >
                {EXPORT_DATE_FORMATS.map((entry) => {
                  const active = entry.id === settings.dateFormat;
                  return (
                    <Box
                      key={entry.id}
                      component="button"
                      type="button"
                      aria-pressed={active}
                      onClick={() => setSettings((c) => ({ ...c, dateFormat: entry.id }))}
                      sx={{
                        display: 'flex',
                        alignItems: 'baseline',
                        gap: 1,
                        textAlign: 'left',
                        px: 1.25,
                        py: 0.75,
                        borderRadius: 2,
                        border: '1px solid',
                        borderColor: active ? 'primary.main' : 'divider',
                        bgcolor: (t) =>
                          active ? alpha(t.palette.primary.main, 0.06) : 'transparent',
                        cursor: 'pointer',
                        '&:hover': { borderColor: 'primary.main' },
                      }}
                    >
                      {/* The sample leads: "European" means nothing until you see it. */}
                      <Typography
                        sx={{
                          fontSize: 13,
                          fontWeight: active ? 700 : 500,
                          fontFamily: 'monospace',
                        }}
                      >
                        {entry.sample}
                      </Typography>
                      <Typography sx={{ fontSize: 11.5, color: 'text.secondary' }}>
                        {entry.label}
                      </Typography>
                    </Box>
                  );
                })}
              </Box>
            </Box>
          </Stack>
        )}
      </DialogContent>

      {/* The commitment line: an export has no preview and no undo once it is in
          the downloads folder, so what it will contain is stated before it runs. */}
      <Box sx={{ px: 3, py: 1.25, bgcolor: (t) => t.qnop.surface2 }}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          {format?.label} {format?.extension} · {selected.length} of {EXPORT_FIELDS.length}{' '}
          {format?.fieldNoun}
          {rows !== null && ` · ${rows} annotation${rows === 1 ? '' : 's'}`}
          {settings.includeComments && ' · with comment threads'}
        </Typography>
      </Box>
      <Divider />

      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} disabled={exporting}>
          Cancel
        </Button>
        <Box sx={{ flex: 1 }} />
        {step > 0 && (
          <Button
            startIcon={<ArrowLeft size={15} />}
            onClick={() => setStep(0)}
            disabled={exporting}
          >
            Back
          </Button>
        )}
        {step === 0 ? (
          <Button variant="contained" endIcon={<ArrowRight size={15} />} onClick={() => setStep(1)}>
            Next
          </Button>
        ) : (
          <Button
            variant="contained"
            startIcon={
              exporting ? <CircularProgress size={14} color="inherit" /> : <Download size={15} />
            }
            disabled={exporting || rows === 0}
            onClick={start}
          >
            Export
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}

/**
 * One switch with a title and an explanation, used for every optional inclusion.
 *
 * <p>Shared so the options cannot drift into looking like different kinds of
 * decision — they are the same kind: something extra that either goes in or does
 * not.
 */
function ToggleCard({
  on,
  onChange,
  icon,
  title,
  hint,
}: {
  on: boolean;
  onChange: (on: boolean) => void;
  icon: React.ReactNode;
  title: string;
  hint: string;
}) {
  return (
    <Box
      sx={{
        px: 1.5,
        py: 1,
        borderRadius: 2,
        border: '1px solid',
        borderColor: on ? 'primary.main' : 'divider',
        bgcolor: (t) => (on ? alpha(t.palette.primary.main, 0.06) : 'transparent'),
        transition: 'border-color 150ms, background-color 150ms',
      }}
    >
      <FormControlLabel
        sx={{ m: 0, width: '100%', alignItems: 'flex-start' }}
        control={
          <Switch
            size="small"
            sx={{ mt: 0.25, mr: 1 }}
            checked={on}
            onChange={(event) => onChange(event.target.checked)}
          />
        }
        label={
          <Box>
            <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
              {icon}
              <Typography sx={{ fontSize: 13.5, fontWeight: 700 }}>{title}</Typography>
            </Stack>
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>{hint}</Typography>
          </Box>
        }
      />
    </Box>
  );
}

/** Sentence case for a noun that lives lowercase in the format registry. */
function capitalise(word: string): string {
  return word.charAt(0).toUpperCase() + word.slice(1);
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
      <Check size={13} />
      <Typography sx={{ fontSize: 12.5, fontWeight: 700 }}>{children}</Typography>
    </Stack>
  );
}
