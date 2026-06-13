import { createTheme } from '@mui/material/styles';

/**
 * Deliberate, restrained palette for an enterprise review tool: a deep indigo
 * primary against neutral paper surfaces, with a burnt accent reserved for
 * semantic emphasis (open annotations, required action). Not the MUI default.
 */
export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: '#34386b' },
    secondary: { main: '#c2410c' },
    background: { default: '#f3f4f8', paper: '#ffffff' },
    text: { primary: '#1c1d29', secondary: '#5b5d72' },
  },
  typography: {
    fontFamily: '"Inter", system-ui, -apple-system, "Segoe UI", sans-serif',
    h1: {
      fontSize: 'clamp(2rem, 1.4rem + 2.5vw, 3.25rem)',
      fontWeight: 700,
      letterSpacing: '-0.025em',
    },
    h6: { fontWeight: 600, letterSpacing: '-0.01em' },
    button: { textTransform: 'none', fontWeight: 600 },
  },
  shape: { borderRadius: 10 },
});
